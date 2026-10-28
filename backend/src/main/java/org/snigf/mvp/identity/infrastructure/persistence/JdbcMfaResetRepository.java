// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.identity.domain.MfaResetRepository;
import org.snigf.mvp.identity.domain.MfaResetRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code mfa_reset_request} (V10), with the person's name and the offices of their active roles. */
@Repository
public class JdbcMfaResetRepository implements MfaResetRepository {

    private static final String SELECT = """
            SELECT r.id, r.user_id, u.display_name, u.email,
                   coalesce((SELECT array_agg(DISTINCT o.code ORDER BY o.code) FROM role_assignment a
                              JOIN office o ON o.id = a.office_id
                             WHERE a.user_id = r.user_id AND a.revoked_at IS NULL), '{}') AS offices,
                   r.reason, r.requested_by, r.requested_at, r.status, r.decided_by, r.decided_at,
                   r.identity_check, r.decision_reason
              FROM mfa_reset_request r JOIN app_user u ON u.id = r.user_id""";

    private final JdbcClient jdbc;

    public JdbcMfaResetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(UUID id, UUID userId, String reason, UUID requestedBy) {
        try {
            jdbc.sql("INSERT INTO mfa_reset_request (id, user_id, reason, requested_by) VALUES (:id, :user, :reason, :by)")
                    .param("id", id).param("user", userId).param("reason", reason).param("by", requestedBy)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new OpenRequestExists();
        }
    }

    @Override
    public Optional<MfaResetRequest> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query((rs, n) -> request(rs)).optional();
    }

    @Override
    public List<MfaResetRequest> list(MfaResetRequest.Status status, int limit) {
        return jdbc.sql(SELECT + " WHERE (:status = '' OR r.status = :status) ORDER BY r.requested_at DESC LIMIT :limit")
                .param("status", status == null ? "" : status.name()).param("limit", limit)
                .query((rs, n) -> request(rs)).list();
    }

    @Override
    public boolean decide(UUID id, MfaResetRequest.Status decision, UUID decidedBy, String identityCheck, String reason) {
        return jdbc.sql("""
                        UPDATE mfa_reset_request SET status = :status, decided_by = :by, decided_at = now(),
                               identity_check = :check, decision_reason = :reason
                         WHERE id = :id AND status = 'PENDING'""")
                .param("status", decision.name()).param("by", decidedBy).param("check", identityCheck)
                .param("reason", reason).param("id", id)
                .update() == 1;
    }

    private static MfaResetRequest request(ResultSet rs) throws SQLException {
        String[] offices = (String[]) rs.getArray("offices").getArray();
        return new MfaResetRequest(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("display_name"), rs.getString("email"), Arrays.asList(offices), rs.getString("reason"),
                rs.getObject("requested_by", UUID.class), instant(rs, "requested_at"),
                MfaResetRequest.Status.valueOf(rs.getString("status")), rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"), rs.getString("identity_check"), rs.getString("decision_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
