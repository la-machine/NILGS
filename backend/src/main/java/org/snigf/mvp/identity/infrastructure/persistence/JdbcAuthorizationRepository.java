// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.identity.domain.AuthorizationRepository;
import org.snigf.mvp.identity.domain.RoleChangeRequest;
import org.snigf.mvp.identity.domain.RoleDefinition;
import org.snigf.mvp.identity.domain.SpecialGrant;
import org.snigf.mvp.shared.security.Permission.Scope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tables of V7 as {@code snigf_app}. The use counter of a special grant changes in one
 * conditional UPDATE, so concurrent actions can never use a grant more often than allowed.
 */
@Repository
public class JdbcAuthorizationRepository implements AuthorizationRepository {

    private static final String GRANT_COLUMNS = """
            g.id, g.user_id, g.permission, o.code AS office_code, g.reason, g.valid_until, g.max_uses, g.uses,
            g.status, g.requested_by, g.requested_at, g.decided_by, g.decided_at, g.decision_reason,
            g.revoked_by, g.revoked_at, g.revoke_reason""";

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcAuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- Roles ----------------------------------------------------------------------------

    @Override
    public List<RoleDefinition> roles() {
        Map<String, Map<String, Scope>> permissions = new HashMap<>();
        jdbc.sql("SELECT role_code, permission, scope FROM role_permission").query(rs -> {
            permissions.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                    .put(rs.getString(2), Scope.valueOf(rs.getString(3)));
        });
        return jdbc.sql("""
                        SELECT code, name_fr, name_en, description, system, assignable, status = 'ACTIVE'
                          FROM role_definition ORDER BY system DESC, code""")
                .query((rs, n) -> new RoleDefinition(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7),
                        permissions.getOrDefault(rs.getString(1), Map.of())))
                .list();
    }

    @Override
    public Optional<RoleDefinition> role(String code) {
        return roles().stream().filter(r -> r.code().equals(code)).findFirst();
    }

    @Override
    public List<UUID> activeHolders(String roleCode) {
        return jdbc.sql("SELECT DISTINCT user_id FROM role_assignment WHERE role = :role AND revoked_at IS NULL")
                .param("role", roleCode)
                .query(UUID.class)
                .list();
    }

    // --- Role change requests --------------------------------------------------------------

    @Override
    public void createRoleRequest(RoleChangeRequest request) {
        try {
            jdbc.sql("""
                            INSERT INTO role_change_request (id, kind, role_code, proposal, reason, requested_by, requested_at)
                            VALUES (:id, :kind, :code, CAST(:proposal AS jsonb), :reason, :by, :at)""")
                    .param("id", request.id()).param("kind", request.kind().name()).param("code", request.roleCode())
                    .param("proposal", proposalJson(request.proposal())).param("reason", request.reason())
                    .param("by", request.requestedBy()).param("at", utc(request.requestedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new OpenRequestExists();
        }
    }

    @Override
    public Optional<RoleChangeRequest> roleRequest(UUID id) {
        return jdbc.sql("SELECT * FROM role_change_request WHERE id = :id").param("id", id)
                .query((rs, n) -> roleRequest(rs)).optional();
    }

    @Override
    public List<RoleChangeRequest> roleRequests(RoleChangeRequest.Status status, int limit) {
        return jdbc.sql("SELECT * FROM role_change_request WHERE (:status = '' OR status = :status)"
                        + " ORDER BY requested_at DESC LIMIT :limit")
                .param("status", status == null ? "" : status.name()).param("limit", limit)
                .query((rs, n) -> roleRequest(rs)).list();
    }

    @Override
    public boolean decideRoleRequest(UUID id, RoleChangeRequest.Status decision, UUID decidedBy, String reason, Instant at) {
        return jdbc.sql("""
                        UPDATE role_change_request SET status = :status, decided_by = :by, decided_at = :at,
                               decision_reason = :reason
                         WHERE id = :id AND status = 'PENDING'""")
                .param("status", decision.name()).param("by", decidedBy).param("at", utc(at)).param("reason", reason)
                .param("id", id)
                .update() == 1;
    }

    private RoleChangeRequest roleRequest(ResultSet rs) throws SQLException {
        JsonNode p = json.readTree(rs.getString("proposal"));
        Map<String, Scope> permissions = new LinkedHashMap<>();
        p.path("permissions").properties().forEach(e -> permissions.put(e.getKey(), Scope.valueOf(e.getValue().asString(""))));
        return new RoleChangeRequest(rs.getObject("id", UUID.class),
                RoleChangeRequest.Kind.valueOf(rs.getString("kind")), rs.getString("role_code"),
                new RoleChangeRequest.Proposal(text(p, "nameFr"), text(p, "nameEn"), text(p, "description"), permissions),
                rs.getString("reason"), rs.getObject("requested_by", UUID.class), instant(rs, "requested_at"),
                RoleChangeRequest.Status.valueOf(rs.getString("status")), rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"), rs.getString("decision_reason"));
    }

    private String proposalJson(RoleChangeRequest.Proposal proposal) {
        ObjectNode node = json.createObjectNode();
        node.put("nameFr", proposal.nameFr());
        node.put("nameEn", proposal.nameEn());
        node.put("description", proposal.description());
        ObjectNode permissions = node.putObject("permissions");
        proposal.permissions().forEach((code, scope) -> permissions.put(code, scope.name()));
        return json.writeValueAsString(node);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString("");
    }

    // --- Special grants --------------------------------------------------------------------

    @Override
    public void createGrant(SpecialGrant g) {
        jdbc.sql("""
                        INSERT INTO permission_grant (id, user_id, permission, office_id, reason, valid_until, max_uses,
                                                      requested_by, requested_at)
                        VALUES (:id, :user, :permission, (SELECT id FROM office WHERE code = :office), :reason,
                                :until, :max, :by, :at)""")
                .param("id", g.id()).param("user", g.userId()).param("permission", g.permission())
                .param("office", g.officeCode()).param("reason", g.reason())
                .param("until", g.validUntil() == null ? null : utc(g.validUntil()))
                .param("max", g.maxUses()).param("by", g.requestedBy()).param("at", utc(g.requestedAt()))
                .update();
    }

    @Override
    public Optional<SpecialGrant> grant(UUID id) {
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM permission_grant g LEFT JOIN office o ON o.id = g.office_id"
                        + " WHERE g.id = :id")
                .param("id", id).query((rs, n) -> grant(rs)).optional();
    }

    @Override
    public List<SpecialGrant> grants(UUID userId, SpecialGrant.Status status, int limit) {
        StringBuilder where = new StringBuilder("TRUE");
        Map<String, Object> params = new HashMap<>();
        if (userId != null) {
            where.append(" AND g.user_id = :user");
            params.put("user", userId);
        }
        if (status != null) {
            where.append(" AND g.status = :status");
            params.put("status", status.name());
        }
        params.put("limit", limit);
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM permission_grant g LEFT JOIN office o ON o.id = g.office_id"
                        + " WHERE " + where + " ORDER BY g.requested_at DESC LIMIT :limit")
                .params(params).query((rs, n) -> grant(rs)).list();
    }

    @Override
    public List<SpecialGrant> grantsInForce(UUID userId, Instant now) {
        return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM permission_grant g LEFT JOIN office o ON o.id = g.office_id"
                        + " WHERE g.user_id = :user AND g.status = 'APPROVED'"
                        + " AND (g.valid_until IS NULL OR g.valid_until > :now)"
                        + " AND (g.max_uses IS NULL OR g.uses < g.max_uses) ORDER BY g.permission")
                .param("user", userId).param("now", utc(now)).query((rs, n) -> grant(rs)).list();
    }

    @Override
    public boolean decideGrant(UUID id, SpecialGrant.Status decision, UUID decidedBy, String reason, Instant at) {
        return jdbc.sql("""
                        UPDATE permission_grant SET status = :status, decided_by = :by, decided_at = :at,
                               decision_reason = :reason
                         WHERE id = :id AND status = 'PENDING'""")
                .param("status", decision.name()).param("by", decidedBy).param("at", utc(at)).param("reason", reason)
                .param("id", id)
                .update() == 1;
    }

    @Override
    public boolean revokeGrant(UUID id, UUID revokedBy, String reason, Instant at) {
        return jdbc.sql("""
                        UPDATE permission_grant SET status = 'REVOKED', revoked_by = :by, revoked_at = :at,
                               revoke_reason = :reason
                         WHERE id = :id AND status IN ('PENDING', 'APPROVED')""")
                .param("by", revokedBy).param("at", utc(at)).param("reason", reason).param("id", id)
                .update() == 1;
    }

    @Override
    public boolean reserveUse(UUID grantId, Instant now) {
        return jdbc.sql("""
                        UPDATE permission_grant SET uses = uses + 1
                         WHERE id = :id AND status = 'APPROVED'
                           AND (valid_until IS NULL OR valid_until > :now)
                           AND (max_uses IS NULL OR uses < max_uses)""")
                .param("id", grantId).param("now", utc(now))
                .update() == 1;
    }

    @Override
    public void releaseUse(UUID grantId) {
        jdbc.sql("UPDATE permission_grant SET uses = uses - 1 WHERE id = :id AND uses > 0").param("id", grantId).update();
    }

    @Override
    public Optional<UUID> officeId(String officeCode) {
        return jdbc.sql("SELECT id FROM office WHERE code = :code").param("code", officeCode == null ? "" : officeCode)
                .query(UUID.class).optional();
    }

    private static SpecialGrant grant(ResultSet rs) throws SQLException {
        Integer max = rs.getObject("max_uses", Integer.class);
        return new SpecialGrant(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("permission"), rs.getString("office_code"), rs.getString("reason"),
                instant(rs, "valid_until"), max, rs.getInt("uses"),
                SpecialGrant.Status.valueOf(rs.getString("status")), rs.getObject("requested_by", UUID.class),
                instant(rs, "requested_at"), rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"),
                rs.getString("decision_reason"), rs.getObject("revoked_by", UUID.class), instant(rs, "revoked_at"),
                rs.getString("revoke_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
