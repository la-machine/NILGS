// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.snigf.mvp.identity.domain.SecondFactorRepository;
import org.snigf.mvp.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code app_user.mfa_enrolled_at} and {@code mfa_recovery_code} (V1 grants). */
@Repository
public class JdbcSecondFactorRepository implements SecondFactorRepository {

    private final JdbcClient jdbc;

    public JdbcSecondFactorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void completeEnrolment(UUID userId, Instant at) {
        jdbc.sql("UPDATE app_user SET mfa_enrolled_at = :at, version = version + 1 WHERE id = :id")
                .param("at", utc(at))
                .param("id", userId)
                .update();
    }

    @Override
    public void replaceRecoveryCodes(UUID userId, List<String> hashes, Instant at) {
        jdbc.sql("""
                        UPDATE mfa_recovery_code SET invalidated_at = :at
                         WHERE user_id = :user AND used_at IS NULL AND invalidated_at IS NULL""")
                .param("at", utc(at))
                .param("user", userId)
                .update();
        for (String hash : hashes) {
            jdbc.sql("INSERT INTO mfa_recovery_code (id, user_id, code_hash) VALUES (:id, :user, :hash)")
                    .param("id", UuidV7.next())
                    .param("user", userId)
                    .param("hash", hash)
                    .update();
        }
    }

    @Override
    public List<StoredRecoveryCode> unusedRecoveryCodes(UUID userId) {
        return jdbc.sql("""
                        SELECT id, code_hash FROM mfa_recovery_code
                         WHERE user_id = :user AND used_at IS NULL AND invalidated_at IS NULL""")
                .param("user", userId)
                .query((rs, n) -> new StoredRecoveryCode(rs.getObject("id", UUID.class), rs.getString("code_hash")))
                .list();
    }

    @Override
    public boolean consumeRecoveryCode(UUID codeId, Instant at) {
        return jdbc.sql("""
                        UPDATE mfa_recovery_code SET used_at = :at
                         WHERE id = :id AND used_at IS NULL AND invalidated_at IS NULL""")
                .param("at", utc(at))
                .param("id", codeId)
                .update() == 1;
    }

    @Override
    public int resetSecondFactor(UUID userId, Instant at) {
        int removed = jdbc.sql("""
                        DELETE FROM user_credentials c USING user_entities e
                         WHERE c.user_entity_user_id = e.id AND e.app_user_id = :user""")
                .param("user", userId)
                .update();
        replaceRecoveryCodes(userId, List.of(), at);
        jdbc.sql("UPDATE app_user SET mfa_enrolled_at = NULL, version = version + 1 WHERE id = :id")
                .param("id", userId)
                .update();
        return removed;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
