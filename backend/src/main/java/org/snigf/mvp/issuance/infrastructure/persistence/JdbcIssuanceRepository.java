// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.issuance.domain.DocumentType;
import org.snigf.mvp.issuance.domain.IssuanceRepository;
import org.snigf.mvp.issuance.domain.IssuedDocument;
import org.snigf.mvp.issuance.domain.Verification;
import org.snigf.mvp.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The issuance registry in PostgreSQL (V20). Issued documents are read under row-level security;
 * the database's guards decide what may be stored and which status changes are allowed;
 * verification reads its evidence through {@code issued_document_evidence}.
 */
@Repository
class JdbcIssuanceRepository implements IssuanceRepository {

    private static final String DOCUMENT = """
            SELECT d.id, d.number, d.doc_type, d.template_version, d.subject_type, d.subject_id, d.subject_ref,
                   o.code AS office_code, d.issued_to, d.issued_at, d.printed::text AS printed, d.content_sha256,
                   d.manifest, d.signature, d.key_version, d.status, d.status_reason, d.status_changed_at,
                   r.number AS replacement
              FROM issued_document d
              LEFT JOIN office o ON o.id = d.office_id
              LEFT JOIN issued_document r ON r.id = d.superseded_by
            """;
    private static final String INCIDENT = """
            SELECT i.id, i.kind, i.status, i.opened_at, d.number AS document_number, v.channel, v.claimed_number,
                   v.presented_sha256, v.reason, i.resolution, i.note, u.display_name AS resolved_by_name,
                   i.resolved_at, i.version
              FROM verification_incident i
              JOIN document_verification v ON v.id = i.verification_id
              LEFT JOIN issued_document d ON d.id = i.issued_document_id
              LEFT JOIN app_user u ON u.id = i.resolved_by
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcIssuanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockOperation(String operationKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('snigf.issuance:' || :key, 0))")
                .param("key", operationKey).query().singleRow();
    }

    @Override
    public Optional<IssuedDocument> findByOperation(String operationKey) {
        return jdbc.sql(DOCUMENT + " WHERE d.operation_key = :key").param("key", operationKey).query(this::document).optional();
    }

    @Override
    public Optional<IssuedDocument> findByNumber(String number) {
        return jdbc.sql(DOCUMENT + " WHERE d.number = :number").param("number", number).query(this::document).optional();
    }

    @Override
    public List<IssuedDocument> ofSubject(UUID subjectId, DocumentType type) {
        return jdbc.sql(DOCUMENT + " WHERE d.subject_id = :id AND d.doc_type = :type ORDER BY d.issued_at DESC, d.id DESC")
                .param("id", subjectId).param("type", type.name()).query(this::document).list();
    }

    @Override
    public UUID insert(IssuedDocument d, String operationKey, UUID supersedes, byte[] pdf) {
        UUID id = jdbc.sql("""
                        INSERT INTO issued_document (number, doc_type, template_version, operation_key, subject_type,
                                                     subject_id, subject_ref, office_id, issued_to, issued_at, printed,
                                                     content_sha256, manifest, signature, key_version, supersedes)
                        VALUES (:number, :type, :template, :operation, :subjectType, :subjectId, :subjectRef,
                                (SELECT id FROM office WHERE code = :office), :issuedTo, :issuedAt, CAST(:printed AS jsonb),
                                :sha, :manifest, :signature, :keyVersion, :supersedes)
                        RETURNING id""")
                .param("number", d.number()).param("type", d.type().name()).param("template", d.templateVersion())
                .param("operation", operationKey).param("subjectType", d.subjectType()).param("subjectId", d.subjectId())
                .param("subjectRef", d.subjectRef()).param("office", d.officeCode()).param("issuedTo", d.issuedTo())
                .param("issuedAt", OffsetDateTime.ofInstant(d.issuedAt(), ZoneOffset.UTC))
                .param("printed", json.writeValueAsString(d.printed())).param("sha", d.sha256())
                .param("manifest", d.manifest()).param("signature", d.signature()).param("keyVersion", d.keyVersion())
                .param("supersedes", supersedes)
                .query(UUID.class).single();
        jdbc.sql("INSERT INTO issued_document_artifact (document_id, media_type, bytes) VALUES (:id, 'application/pdf', :bytes)")
                .param("id", id).param("bytes", pdf).update();
        return id;
    }

    @Override
    public Optional<byte[]> artifact(UUID documentId) {
        return jdbc.sql("SELECT bytes FROM issued_document_artifact WHERE document_id = :id").param("id", documentId)
                .query((rs, n) -> rs.getBytes(1)).optional();
    }

    @Override
    public boolean changeStatus(UUID documentId, IssuedDocument.Status status, String reason, UUID actor, UUID supersededBy) {
        return jdbc.sql("""
                        UPDATE issued_document SET status = :status, status_reason = :reason, status_changed_by = :actor,
                                                   status_changed_at = clock_timestamp(), superseded_by = :supersededBy
                         WHERE id = :id AND status = 'VALID'""")
                .param("status", status.name()).param("reason", reason).param("actor", actor)
                .param("supersededBy", supersededBy).param("id", documentId).update() == 1;
    }

    @Override
    public Optional<Verification.Evidence> evidenceByNumber(String number) {
        return jdbc.sql("SELECT * FROM issued_document_evidence(:number, NULL)").param("number", number)
                .query(JdbcIssuanceRepository::evidence).optional();
    }

    @Override
    public Optional<Verification.Evidence> evidenceBySha(byte[] sha256) {
        return jdbc.sql("SELECT * FROM issued_document_evidence(NULL, :sha)").param("sha", sha256)
                .query(JdbcIssuanceRepository::evidence).optional();
    }

    @Override
    public UUID recordVerification(Verification.Channel channel, String claimedNumber, byte[] presentedSha256, UUID documentId,
                                   Verification.Outcome outcome, String reason, UUID actor) {
        // The id is made here: RETURNING would need the read policy, which an anonymous verifier lacks.
        UUID id = UuidV7.next();
        jdbc.sql("""
                        INSERT INTO document_verification (id, channel, claimed_number, presented_sha256, issued_document_id,
                                                           outcome, reason, actor_id)
                        VALUES (:id, :channel, :claimed, :sha, :document, :outcome, :reason, :actor)""")
                .param("id", id).param("channel", channel.name()).param("claimed", claimedNumber).param("sha", presentedSha256)
                .param("document", documentId).param("outcome", outcome.name()).param("reason", reason)
                .param("actor", actor).update();
        return id;
    }

    @Override
    public UUID openIncident(UUID verificationId, Verification.IncidentKind kind, UUID documentId) {
        UUID id = UuidV7.next();
        jdbc.sql("""
                        INSERT INTO verification_incident (id, verification_id, kind, issued_document_id)
                        VALUES (:id, :verification, :kind, :document)""")
                .param("id", id).param("verification", verificationId).param("kind", kind.name()).param("document", documentId)
                .update();
        return id;
    }

    @Override
    public List<Incident> incidents(Incident.Status status, int limit, int offset) {
        return jdbc.sql(INCIDENT + " WHERE (CAST(:status AS text) IS NULL OR i.status = :status)"
                        + " ORDER BY i.opened_at DESC, i.id LIMIT :limit OFFSET :offset")
                .param("status", status == null ? null : status.name()).param("limit", limit).param("offset", offset)
                .query(JdbcIssuanceRepository::incident).list();
    }

    @Override
    public Optional<Incident> incident(UUID id) {
        return jdbc.sql(INCIDENT + " WHERE i.id = :id").param("id", id).query(JdbcIssuanceRepository::incident).optional();
    }

    @Override
    public boolean resolveIncident(UUID id, long version, Incident.Resolution resolution, String note, UUID actor) {
        return jdbc.sql("""
                        UPDATE verification_incident SET status = 'RESOLVED', resolution = :resolution, note = :note,
                                                         resolved_by = :actor, resolved_at = clock_timestamp(),
                                                         version = version + 1
                         WHERE id = :id AND version = :version AND status = 'OPEN'""")
                .param("resolution", resolution.name()).param("note", note).param("actor", actor).param("id", id)
                .param("version", version).update() == 1;
    }

    private IssuedDocument document(ResultSet rs, int n) throws SQLException {
        Map<String, String> printed = json.readValue(rs.getString("printed"), new TypeReference<Map<String, String>>() { });
        return new IssuedDocument(rs.getObject("id", UUID.class), rs.getString("number"),
                DocumentType.valueOf(rs.getString("doc_type")), rs.getString("template_version"), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getString("subject_ref"), rs.getString("office_code"),
                rs.getObject("issued_to", UUID.class), instant(rs, "issued_at"), printed, rs.getBytes("content_sha256"),
                rs.getBytes("manifest"), rs.getString("signature"), rs.getInt("key_version"),
                IssuedDocument.Status.valueOf(rs.getString("status")), rs.getString("status_reason"),
                instant(rs, "status_changed_at"), rs.getString("replacement"));
    }

    private static Verification.Evidence evidence(ResultSet rs, int n) throws SQLException {
        return new Verification.Evidence(rs.getObject("id", UUID.class), rs.getString("number"),
                DocumentType.valueOf(rs.getString("doc_type")), rs.getString("template_version"),
                rs.getString("subject_type"), rs.getString("subject_ref"), instant(rs, "issued_at"),
                rs.getString("office_code"), rs.getString("office_name_fr"), rs.getString("office_name_en"),
                rs.getBytes("content_sha256"), rs.getBytes("manifest"), rs.getString("signature"), rs.getInt("key_version"),
                IssuedDocument.Status.valueOf(rs.getString("status")), rs.getString("replacement_number"));
    }

    private static Incident incident(ResultSet rs, int n) throws SQLException {
        byte[] sha = rs.getBytes("presented_sha256");
        String resolution = rs.getString("resolution");
        return new Incident(rs.getObject("id", UUID.class), Verification.IncidentKind.valueOf(rs.getString("kind")),
                Incident.Status.valueOf(rs.getString("status")), instant(rs, "opened_at"), rs.getString("document_number"),
                Verification.Channel.valueOf(rs.getString("channel")), rs.getString("claimed_number"),
                sha == null ? null : HexFormat.of().formatHex(sha), rs.getString("reason"),
                resolution == null ? null : Incident.Resolution.valueOf(resolution), rs.getString("note"),
                rs.getString("resolved_by_name"), instant(rs, "resolved_at"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
