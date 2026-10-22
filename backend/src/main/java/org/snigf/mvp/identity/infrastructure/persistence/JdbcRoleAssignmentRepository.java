// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.domain.RoleAssignmentRepository;
import org.snigf.mvp.identity.domain.RoleAssignmentRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code role_assignment}, {@code role_assignment_request}, {@code office} and the
 * administrator's account directory, as {@code snigf_app}. The search matches with {@code position()}, not {@code LIKE}, so the
 * administrator's text is never interpreted as a pattern.
 */
@Repository
public class JdbcRoleAssignmentRepository implements RoleAssignmentRepository {

    private final JdbcClient jdbc;

    public JdbcRoleAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> officeId(String officeCode) {
        return jdbc.sql("SELECT id FROM office WHERE code = :code")
                .param("code", officeCode == null ? "" : officeCode)
                .query(UUID.class)
                .optional();
    }

    @Override
    public void createRequest(UUID id, UUID userId, String role, UUID officeId, UUID requestedBy, String reason) {
        try {
            jdbc.sql("""
                            INSERT INTO role_assignment_request (id, user_id, role, office_id, requested_by, reason)
                            VALUES (:id, :user, :role, :office, :by, :reason)""")
                    .param("id", id)
                    .param("user", userId)
                    .param("role", role)
                    .param("office", officeId)
                    .param("by", requestedBy)
                    .param("reason", reason)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new OpenRequestExists();
        }
    }

    @Override
    public Optional<RoleAssignmentRequest> request(UUID id) {
        return jdbc.sql(REQUEST_SELECT + " WHERE r.id = :id").param("id", id).query((rs, n) -> request(rs)).optional();
    }

    @Override
    public boolean active(UUID userId, String role, UUID officeId) {
        return jdbc.sql("SELECT count(*) FROM role_assignment WHERE user_id = :user AND role = :role"
                        + " AND office_id = :office AND revoked_at IS NULL")
                .param("user", userId).param("role", role).param("office", officeId)
                .query(Long.class).single() > 0;
    }

    @Override
    public List<RoleAssignmentRequest> requests(RoleAssignmentRequest.Status status, int limit) {
        return jdbc.sql(REQUEST_SELECT + " WHERE (:status = '' OR r.status = :status) ORDER BY r.requested_at DESC LIMIT :limit")
                .param("status", status == null ? "" : status.name()).param("limit", limit)
                .query((rs, n) -> request(rs)).list();
    }

    @Override
    public boolean decide(UUID id, RoleAssignmentRequest.Status decision, UUID decidedBy, String reason) {
        try {
            return jdbc.sql("""
                            UPDATE role_assignment_request SET status = :status, decided_by = :by, decided_at = now(),
                                   decision_reason = :reason
                             WHERE id = :id AND status = 'PENDING'""")
                    .param("status", decision.name()).param("by", decidedBy).param("reason", reason).param("id", id)
                    .update() == 1;
        } catch (DuplicateKeyException e) {
            throw new AlreadyActive();
        }
    }

    private static final String REQUEST_SELECT = """
            SELECT r.id, r.user_id, r.role, o.code AS office_code, r.reason, r.requested_by, r.requested_at, r.status,
                   r.decided_by, r.decided_at, r.decision_reason
              FROM role_assignment_request r JOIN office o ON o.id = r.office_id""";

    private static RoleAssignmentRequest request(ResultSet rs) throws SQLException {
        OffsetDateTime decided = rs.getObject("decided_at", OffsetDateTime.class);
        return new RoleAssignmentRequest(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("role"), rs.getString("office_code"), rs.getString("reason"),
                rs.getObject("requested_by", UUID.class), rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                RoleAssignmentRequest.Status.valueOf(rs.getString("status")), rs.getObject("decided_by", UUID.class),
                decided == null ? null : decided.toInstant(), rs.getString("decision_reason"));
    }

    @Override
    public Optional<Assignment> find(UUID assignmentId) {
        return jdbc.sql("""
                        SELECT r.id, r.user_id, r.role, o.code, r.revoked_at IS NULL AS active
                          FROM role_assignment r LEFT JOIN office o ON o.id = r.office_id WHERE r.id = :id""")
                .param("id", assignmentId)
                .query((rs, n) -> new Assignment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getBoolean(5)))
                .optional();
    }

    @Override
    public boolean revoke(UUID assignmentId, UUID userId, UUID revokedBy, String reason, Instant at) {
        return jdbc.sql("""
                        UPDATE role_assignment SET revoked_by = :by, revoked_at = :at, revoke_reason = :reason
                         WHERE id = :id AND user_id = :user AND revoked_at IS NULL""")
                .param("by", revokedBy)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("reason", reason)
                .param("id", assignmentId)
                .param("user", userId)
                .update() == 1;
    }

    @Override
    public List<AccountSummary> search(String text, int limit, int offset, Instant now) {
        record Row(UUID id, String email, String name, AccountType type, boolean enabled, boolean ready, boolean locked) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT id, email, display_name, account_type, enabled, mfa_enrolled_at IS NOT NULL,
                               coalesce(locked_until > :now, false)
                          FROM app_user
                         WHERE :text = '' OR position(lower(:text) IN lower(email)) > 0
                               OR position(lower(:text) IN lower(display_name)) > 0
                         ORDER BY created_at DESC, id
                         LIMIT :limit OFFSET :offset""")
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("text", text)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        AccountType.valueOf(rs.getString(4)), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AssignmentView>> byUser = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT r.user_id, r.id, r.role, o.code FROM role_assignment r
                          LEFT JOIN office o ON o.id = r.office_id
                         WHERE r.revoked_at IS NULL AND r.user_id IN (:ids)
                         ORDER BY r.role, o.code""")
                .param("ids", rows.stream().map(Row::id).toList())
                .query(rs -> {
                    byUser.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new AssignmentView(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)));
                });
        return rows.stream().map(r -> new AccountSummary(r.id(), r.email(), r.name(), r.type(), r.enabled(), r.ready(),
                r.locked(), byUser.getOrDefault(r.id(), List.of()))).toList();
    }
}
