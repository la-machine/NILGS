// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.domain.CheckpointRepository;
import org.snigf.mvp.keys.Signature;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code audit_checkpoint}: append-only, a convenience copy of the signed checkpoints. */
@Repository
public class JdbcCheckpointRepository implements CheckpointRepository {

    /** Prefix of {@code audit_checkpoint.key_id}; the key version follows. */
    static final String KEY_ID_PREFIX = "audit-checkpoint:v";

    private final JdbcClient jdbc;

    public JdbcCheckpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryLock() {
        return Boolean.TRUE.equals(jdbc.sql(
                        "SELECT pg_try_advisory_xact_lock(hashtextextended('snigf.audit_checkpoint.create', 0))")
                .query(Boolean.class)
                .single());
    }

    @Override
    public void add(Checkpoint checkpoint) {
        jdbc.sql("""
                        INSERT INTO audit_checkpoint (id, chain_pos, head_hash, created_at, signature, key_id)
                        VALUES (:id, :pos, :head, :createdAt, :signature, :keyId)""")
                .param("id", checkpoint.id())
                .param("pos", checkpoint.chainPos())
                .param("head", checkpoint.headHash())
                .param("createdAt", OffsetDateTime.ofInstant(checkpoint.createdAt(), ZoneOffset.UTC))
                .param("signature", checkpoint.signature().value())
                .param("keyId", KEY_ID_PREFIX + checkpoint.signature().keyVersion())
                .update();
    }

    /**
     * A row with a malformed key id is returned with version 1, so it fails signature
     * verification instead of disappearing from the evidence.
     */
    @Override
    public List<Checkpoint> all() {
        return jdbc.sql("SELECT id, chain_pos, head_hash, created_at, signature, key_id FROM audit_checkpoint"
                        + " ORDER BY chain_pos, created_at")
                .query((rs, n) -> new Checkpoint(
                        rs.getObject("id", UUID.class),
                        rs.getLong("chain_pos"),
                        rs.getBytes("head_hash"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        new Signature(keyVersion(rs.getString("key_id")), rs.getBytes("signature"))))
                .list();
    }

    private static int keyVersion(String keyId) {
        if (keyId != null && keyId.startsWith(KEY_ID_PREFIX)) {
            try {
                int version = Integer.parseInt(keyId.substring(KEY_ID_PREFIX.length()));
                if (version >= 1) {
                    return version;
                }
            } catch (NumberFormatException e) {
                // reported as an invalid signature
            }
        }
        return 1;
    }
}
