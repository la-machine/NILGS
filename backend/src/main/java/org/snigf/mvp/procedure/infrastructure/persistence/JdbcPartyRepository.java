// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.procedure.domain.Parties;
import org.snigf.mvp.procedure.domain.PartyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Reads the persons of a case under row-level security (V35, {@code case_party_readable}); writes through V35's functions. */
@Repository
class JdbcPartyRepository implements PartyRepository {

    private static final String CHECK = """
            SELECT c.id, c.outcome, c.note, c.checked_by, u.display_name AS checked_name, c.checked_at
              FROM case_party_check c JOIN app_user u ON u.id = c.checked_by
             WHERE c.target_kind = :kind AND c.target_id = :target""";

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcPartyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Parties.View> view(UUID applicationId) {
        // NULL when the bound officer may not read the case's persons.
        Optional<List<String>> status = jdbc.sql("SELECT case_party_status(:id)").param("id", applicationId)
                .query((rs, n) -> {
                    Array a = rs.getArray(1);
                    return a == null ? Optional.<List<String>>empty() : Optional.of(texts(a));
                }).list().stream().findFirst().flatMap(o -> o);
        if (status.isEmpty()) {
            return Optional.empty();
        }
        List<String> problems = status.get();
        String capacity = jdbc.sql("SELECT declaration ->> 'capacity' FROM application WHERE id = :id").param("id", applicationId)
                .query(String.class).optional().orElse(null);
        List<Parties.Person> persons = jdbc.sql("""
                        SELECT p.id, p.ref, p.kind, p.account_id IS NOT NULL AS applicant_account,
                               ARRAY(SELECT r.role FROM case_role r WHERE r.person_id = p.id ORDER BY r.role) AS roles
                          FROM case_person p WHERE p.application_id = :id ORDER BY p.ref""")
                .param("id", applicationId)
                .query((rs, n) -> new Parties.Person(rs.getObject("id", UUID.class), rs.getString("ref"), rs.getString("kind"),
                        texts(rs.getArray("roles")), rs.getBoolean("applicant_account"), List.of()))
                .list().stream().map(p -> new Parties.Person(p.id(), p.ref(), p.kind(), p.roles(), p.applicantAccount(), identities(p.id())))
                .toList();
        Map<UUID, String> refs = new LinkedHashMap<>();
        persons.forEach(p -> refs.put(p.id(), p.ref()));
        List<Parties.Statement> statements = jdbc.sql("""
                        SELECT s.*, u.display_name AS entered_name FROM case_ownership_statement s JOIN app_user u ON u.id = s.entered_by
                         WHERE s.application_id = :id ORDER BY s.version_no DESC""")
                .param("id", applicationId)
                .query((rs, n) -> new Parties.Statement(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("source"),
                        rs.getObject("entered_by", UUID.class), rs.getString("entered_name"), instant(rs, "entered_at"),
                        rs.getObject("supersedes_id", UUID.class), rs.getString("correction_reason"), rs.getString("verification"), List.of(), null))
                .list().stream().map(s -> new Parties.Statement(s.id(), s.versionNo(), s.source(), s.enteredById(), s.enteredByName(), s.enteredAt(),
                        s.supersedesId(), s.correctionReason(), s.verification(), shares(s.id(), refs), check("OWNERSHIP", s.id())))
                .toList();
        List<Parties.Mandate> mandates = jdbc.sql("""
                        SELECT m.*, u.display_name AS entered_name,
                               ARRAY(SELECT q.ref FROM case_mandate_subject ms JOIN case_person q ON q.id = ms.person_id
                                      WHERE ms.mandate_id = m.id ORDER BY q.ref) AS represents
                          FROM case_mandate m JOIN app_user u ON u.id = m.entered_by
                         WHERE m.application_id = :id ORDER BY m.representative_id, m.version_no DESC""")
                .param("id", applicationId)
                .query((rs, n) -> new Parties.Mandate(rs.getObject("id", UUID.class), rs.getObject("representative_id", UUID.class),
                        null, rs.getInt("version_no"), rs.getString("source"), rs.getString("scope"), texts(rs.getArray("represents")),
                        rs.getObject("document_file_id", UUID.class), rs.getObject("entered_by", UUID.class), rs.getString("entered_name"),
                        instant(rs, "entered_at"), rs.getObject("supersedes_id", UUID.class), rs.getString("correction_reason"),
                        rs.getString("verification"), null))
                .list().stream().map(m -> new Parties.Mandate(m.id(), m.representativeId(), refs.get(m.representativeId()), m.versionNo(), m.source(),
                        m.scope(), m.represents(), m.documentFileId(), m.enteredById(), m.enteredByName(), m.enteredAt(), m.supersedesId(),
                        m.correctionReason(), m.verification(), check("MANDATE", m.id())))
                .toList();
        List<Parties.File> files = jdbc.sql(FILES + " WHERE f.application_id = :id ORDER BY f.uploaded_at, f.id").param("id", applicationId)
                .query((rs, n) -> file(rs)).list();
        return Optional.of(new Parties.View(capacity, persons, statements, mandates, files, problems));
    }

    private static final String FILES = """
            SELECT f.*, u.display_name AS uploaded_name FROM case_party_file f JOIN app_user u ON u.id = f.uploaded_by""";

    private List<Parties.Identity> identities(UUID personId) {
        return jdbc.sql("""
                        SELECT i.*, u.display_name AS entered_name FROM case_person_identity i JOIN app_user u ON u.id = i.entered_by
                         WHERE i.person_id = :person ORDER BY i.version_no DESC""")
                .param("person", personId)
                .query((rs, n) -> new Parties.Identity(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("source"),
                        rs.getString("full_name"), rs.getString("identity_kind"), rs.getString("identity_number"), rs.getString("phone"),
                        rs.getString("postal_address"), rs.getObject("valid_until", java.time.LocalDate.class), rs.getBoolean("expired_at_transcription"),
                        rs.getObject("source_file_id", UUID.class), rs.getObject("source_version_id", UUID.class),
                        rs.getObject("entered_by", UUID.class), rs.getString("entered_name"), instant(rs, "entered_at"),
                        rs.getObject("supersedes_id", UUID.class), rs.getString("correction_reason"), rs.getString("verification"), null, null))
                .list().stream().map(i -> new Parties.Identity(i.id(), i.versionNo(), i.source(), i.fullName(), i.identityKind(), i.identityNumber(),
                        i.phone(), i.postalAddress(), i.validUntil(), i.expiredAtTranscription(), i.sourceFileId(), i.sourceVersionId(), i.enteredById(),
                        i.enteredByName(), i.enteredAt(), i.supersedesId(), i.correctionReason(), i.verification(), check("IDENTITY", i.id()),
                        exception(i.id())))
                .toList();
    }

    private Parties.PolicyException exception(UUID identityId) {
        return jdbc.sql("""
                        SELECT x.id, x.policy, x.reason, x.authorised_by, u.display_name, x.authorised_at
                          FROM case_party_exception x JOIN app_user u ON u.id = x.authorised_by
                         WHERE x.target_kind = 'IDENTITY' AND x.target_id = :id""")
                .param("id", identityId)
                .query((rs, n) -> new Parties.PolicyException(rs.getObject("id", UUID.class), rs.getString("policy"), rs.getString("reason"),
                        rs.getObject("authorised_by", UUID.class), rs.getString("display_name"), instant(rs, "authorised_at")))
                .optional().orElse(null);
    }

    private List<Parties.Share> shares(UUID statementId, Map<UUID, String> refs) {
        return jdbc.sql("SELECT * FROM case_ownership_share WHERE statement_id = :s ORDER BY person_id, interest").param("s", statementId)
                .query((rs, n) -> new Parties.Share(rs.getObject("person_id", UUID.class), refs.get(rs.getObject("person_id", UUID.class)),
                        rs.getString("interest"), rs.getInt("share_num"), rs.getInt("share_den"), rs.getString("usufruct_term"),
                        rs.getObject("usufruct_until", java.time.LocalDate.class), rs.getObject("basis_file_id", UUID.class),
                        rs.getObject("basis_version_id", UUID.class)))
                .list();
    }

    private Parties.Check check(String kind, UUID target) {
        return jdbc.sql(CHECK).param("kind", kind).param("target", target)
                .query((rs, n) -> new Parties.Check(rs.getObject("id", UUID.class), rs.getString("outcome"), rs.getString("note"),
                        rs.getObject("checked_by", UUID.class), rs.getString("checked_name"), instant(rs, "checked_at")))
                .optional().orElse(null);
    }

    @Override
    public UUID addFile(UUID applicationId, String purpose, Parties.KeptFile f) {
        return jdbc.sql("SELECT case_party_file_add(:app, :purpose, :key, :sha, CAST(:size AS bigint), :type, :name)")
                .param("app", applicationId).param("purpose", purpose).param("key", f.storageKey()).param("sha", f.sha256())
                .param("size", f.sizeBytes()).param("type", f.mediaType()).param("name", f.name())
                .query(UUID.class).single();
    }

    @Override
    public UUID recordIdentity(UUID personId, Parties.NewIdentity identity, UUID sourceFile, UUID sourceVersion, UUID supersedes, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (identity != null) {
            body.put("fullName", identity.fullName());
            body.put("identityKind", identity.identityKind());
            body.put("identityNumber", identity.identityNumber());
            if (identity.phone() != null) {
                body.put("phone", identity.phone());
            }
            if (identity.postalAddress() != null) {
                body.put("postalAddress", identity.postalAddress());
            }
            if (identity.validUntil() != null) {
                body.put("validUntil", identity.validUntil().toString());
            }
        }
        return jdbc.sql("SELECT case_identity_record(:person, CAST(:identity AS jsonb), :file, :version, :supersedes, :reason)")
                .param("person", personId).param("identity", json.writeValueAsString(body)).param("file", sourceFile)
                .param("version", sourceVersion).param("supersedes", supersedes).param("reason", reason)
                .query(UUID.class).single();
    }

    @Override
    public UUID recordOwnership(UUID applicationId, List<Parties.NewShare> shares, UUID supersedes, String reason) {
        List<Map<String, Object>> body = shares == null ? List.of() : shares.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("person", s.person());
            m.put("interest", s.interest());
            m.put("shareNum", s.shareNum());
            m.put("shareDen", s.shareDen());
            if (s.usufructTerm() != null) {
                m.put("usufructTerm", s.usufructTerm());
            }
            if (s.usufructUntil() != null) {
                m.put("usufructUntil", s.usufructUntil().toString());
            }
            if (s.basisFile() != null) {
                m.put("basisFile", s.basisFile());
            }
            if (s.basisVersion() != null) {
                m.put("basisVersion", s.basisVersion());
            }
            return m;
        }).toList();
        return jdbc.sql("SELECT case_ownership_record(:app, CAST(:shares AS jsonb), :supersedes, :reason)")
                .param("app", applicationId).param("shares", json.writeValueAsString(body)).param("supersedes", supersedes).param("reason", reason)
                .query(UUID.class).single();
    }

    @Override
    public UUID recordMandate(UUID representativeId, List<UUID> represents, String scope, UUID documentFile, UUID supersedes, String reason) {
        return jdbc.sql("SELECT case_mandate_record(:rep, CAST(:represents AS uuid[]), :scope, :file, :supersedes, :reason)")
                .param("rep", representativeId).param("represents", represents == null ? new UUID[0] : represents.toArray(UUID[]::new))
                .param("scope", scope).param("file", documentFile).param("supersedes", supersedes).param("reason", reason)
                .query(UUID.class).single();
    }

    @Override
    public UUID check(String targetKind, UUID targetId, String outcome, String note) {
        return jdbc.sql("SELECT case_party_check_record(:kind, :target, :outcome, :note)")
                .param("kind", targetKind).param("target", targetId).param("outcome", outcome).param("note", note)
                .query(UUID.class).single();
    }

    @Override
    public UUID authoriseException(UUID identityVersionId, String policy, String reason) {
        return jdbc.sql("SELECT case_party_exception_record(:identity, :policy, :reason)")
                .param("identity", identityVersionId).param("policy", policy).param("reason", reason)
                .query(UUID.class).single();
    }

    @Override
    public Optional<Parties.File> file(UUID applicationId, UUID fileId) {
        return jdbc.sql(FILES + " WHERE f.application_id = :app AND f.id = :id").param("app", applicationId).param("id", fileId)
                .query((rs, n) -> file(rs)).optional();
    }

    @Override
    public Optional<UUID> caseOfPerson(UUID personId) {
        return jdbc.sql("SELECT application_id FROM case_person WHERE id = :id").param("id", personId).query(UUID.class).optional();
    }

    private static Parties.File file(ResultSet rs) throws SQLException {
        return new Parties.File(rs.getObject("id", UUID.class), rs.getString("purpose"), rs.getString("storage_key"), rs.getString("sha256"),
                rs.getLong("size_bytes"), rs.getString("detected_type"), rs.getString("original_name"), rs.getObject("uploaded_by", UUID.class),
                rs.getString("uploaded_name"), instant(rs, "uploaded_at"));
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
