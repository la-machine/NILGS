// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.document.domain.DocumentRepository;
import org.snigf.mvp.document.domain.DocumentType;
import org.snigf.mvp.document.domain.DocumentVersion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Documents under row-level security (V14): every statement sees only what the actor named for
 * the transaction may see. The database numbers versions and refuses any version the owner's
 * draft does not take.
 */
@Repository
class JdbcDocumentRepository implements DocumentRepository {

    private static final String VERSION = """
            SELECT v.id, v.document_id, d.category, v.version_no, v.status, v.storage_key, v.sha256, v.size_bytes,
                   v.detected_type, v.original_name, v.uploaded_at
              FROM document_version v
              JOIN document d ON d.id = v.document_id
            """;

    private final JdbcClient jdbc;

    JdbcDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID documentFor(UUID applicationId, String category) {
        jdbc.sql("""
                        INSERT INTO document (application_id, category) VALUES (:application, :category)
                        ON CONFLICT (application_id, category) DO NOTHING""")
                .param("application", applicationId).param("category", category).update();
        return jdbc.sql("SELECT id FROM document WHERE application_id = :application AND category = :category")
                .param("application", applicationId).param("category", category).query(UUID.class).single();
    }

    @Override
    public int versionCount(UUID documentId) {
        return jdbc.sql("SELECT count(*) FROM document_version WHERE document_id = :document")
                .param("document", documentId).query(Integer.class).single();
    }

    @Override
    public DocumentVersion add(NewVersion v) {
        UUID id = jdbc.sql("""
                        INSERT INTO document_version (document_id, version_no, status, storage_key, sha256, size_bytes,
                                                      detected_type, original_name, uploaded_by)
                        VALUES (:document, 1, 'STORED', :key, :sha256, :size, :type, :name, :by)
                        RETURNING id""")
                .param("document", v.documentId()).param("key", v.storageKey()).param("sha256", v.sha256())
                .param("size", v.sizeBytes()).param("type", v.type().mediaType()).param("name", v.originalName())
                .param("by", v.uploadedBy())
                .query(UUID.class).single();
        return jdbc.sql(VERSION + " WHERE v.id = :id").param("id", id).query((rs, n) -> version(rs)).single();
    }

    @Override
    public List<DocumentVersion> ofApplication(UUID applicationId) {
        return jdbc.sql(VERSION + " WHERE d.application_id = :application ORDER BY d.category, v.version_no DESC")
                .param("application", applicationId).query((rs, n) -> version(rs)).list();
    }

    @Override
    public Optional<DocumentVersion> version(UUID applicationId, UUID versionId) {
        return jdbc.sql(VERSION + " WHERE d.application_id = :application AND v.id = :id")
                .param("application", applicationId).param("id", versionId).query((rs, n) -> version(rs)).optional();
    }

    @Override
    public Set<String> knownKeys(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT * FROM document_storage_keys_known(CAST(:keys AS text[]))")
                .param("keys", keys.toArray(String[]::new)).query(String.class).list());
    }

    private static DocumentVersion version(ResultSet rs) throws SQLException {
        return new DocumentVersion(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getString("category"), rs.getInt("version_no"), DocumentVersion.Status.valueOf(rs.getString("status")),
                rs.getString("storage_key"), rs.getString("sha256"), rs.getLong("size_bytes"),
                DocumentType.ofMediaType(rs.getString("detected_type")).orElseThrow(), rs.getString("original_name"),
                instant(rs, "uploaded_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
