// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.application.AuditProperties;
import org.snigf.mvp.audit.domain.Actor;
import org.snigf.mvp.audit.domain.AuditEventRepository;
import org.snigf.mvp.audit.domain.ChainHead;
import org.snigf.mvp.audit.domain.ChainedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code audit_event} as {@code snigf_app}, which may only SELECT and INSERT
 * (V3__audit.sql). The insert names no position, time or hash: the trigger assigns them.
 */
@Repository
public class JdbcAuditEventRepository implements AuditEventRepository {

    private static final String COLUMNS = """
            chain_pos, id, occurred_at, actor_type, actor_id, actor_label, actor_roles, action,
            entity_type, entity_id, entity_ref, outcome, reason, correlation_id,
            host(source_ip) AS source_ip, details::text AS details, prev_hash, hash""";

    private final JdbcClient jdbc;
    private final JdbcTemplate streaming;

    public JdbcAuditEventRepository(JdbcClient jdbc, DataSource dataSource, AuditProperties properties) {
        this.jdbc = jdbc;
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(properties.verifyFetchSize());
    }

    @Override
    public long append(UUID id, Actor actor, AuditEvent event, UUID correlationId, String sourceIp,
                       String detailsJson) {
        return jdbc.sql("""
                        INSERT INTO audit_event (id, actor_type, actor_id, actor_label, actor_roles, action,
                                                 entity_type, entity_id, entity_ref, outcome, reason,
                                                 correlation_id, source_ip, details)
                        VALUES (:id, :actorType, CAST(:actorId AS uuid), :actorLabel, CAST(:actorRoles AS text[]),
                                :action, :entityType, CAST(:entityId AS uuid), :entityRef, :outcome, :reason,
                                :correlationId, CAST(:sourceIp AS inet), CAST(:details AS jsonb))
                        RETURNING chain_pos""")
                .param("id", id)
                .param("actorType", actor.type())
                .param("actorId", actor.userId() == null ? null : actor.userId().toString())
                .param("actorLabel", actor.label())
                // Actor guarantees role names match [A-Z][A-Z0-9_]*: the literal needs no quoting.
                .param("actorRoles", "{" + String.join(",", actor.roles()) + "}")
                .param("action", event.action().name())
                .param("entityType", event.entityType())
                .param("entityId", event.entityId() == null ? null : event.entityId().toString())
                .param("entityRef", event.entityRef())
                .param("outcome", event.outcome().name())
                .param("reason", event.reason())
                .param("correlationId", correlationId)
                .param("sourceIp", sourceIp)
                .param("details", detailsJson)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<ChainHead> head() {
        return jdbc.sql("SELECT chain_pos, hash FROM audit_event ORDER BY chain_pos DESC LIMIT 1")
                .query((rs, n) -> new ChainHead(rs.getLong(1), rs.getBytes(2)))
                .optional();
    }

    @Override
    public Optional<byte[]> hashAt(long chainPos) {
        return jdbc.sql("SELECT hash FROM audit_event WHERE chain_pos = :pos")
                .param("pos", chainPos)
                .query((rs, n) -> rs.getBytes(1))
                .optional();
    }

    /**
     * Streams through a server-side cursor. Must run inside a transaction: PostgreSQL uses
     * the fetch size only when auto-commit is off.
     */
    @Override
    public void forEach(long fromPos, long toPos, Consumer<ChainedEvent> action) {
        streaming.query("SELECT " + COLUMNS + " FROM audit_event WHERE chain_pos BETWEEN ? AND ? ORDER BY chain_pos",
                rs -> {
                    action.accept(event(rs));
                }, fromPos, toPos);
    }

    private static ChainedEvent event(ResultSet rs) throws SQLException {
        return new ChainedEvent(
                rs.getLong("chain_pos"),
                rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("actor_type"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_label"),
                roles(rs.getArray("actor_roles")),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getString("entity_ref"),
                rs.getString("outcome"),
                rs.getString("reason"),
                rs.getObject("correlation_id", UUID.class),
                rs.getString("source_ip"),
                rs.getString("details"),
                rs.getBytes("prev_hash"),
                rs.getBytes("hash"));
    }

    private static List<String> roles(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
