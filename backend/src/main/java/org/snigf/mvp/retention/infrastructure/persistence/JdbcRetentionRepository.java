// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.retention.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.retention.domain.Retention;
import org.snigf.mvp.retention.domain.RetentionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads under row-level security; writes through V30's functions, which check the actor themselves. */
@Repository
class JdbcRetentionRepository implements RetentionRepository {

    private final JdbcClient jdbc;

    JdbcRetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Retention.RetentionClass> classes() {
        return jdbc.sql("SELECT * FROM retention_class ORDER BY code")
                .query((rs, n) -> new Retention.RetentionClass(rs.getString("code"), rs.getString("description_en"), rs.getString("description_fr"),
                        rs.getString("schedule_reference"), date(rs, "schedule_approved_on"), rs.getString("standing")))
                .list();
    }

    private static final String HOLD = """
            SELECT h.*, u.display_name AS placed_by_name, legal_hold_in_force(h.id) AS in_force
              FROM legal_hold h JOIN app_user u ON u.id = h.placed_by
            """;

    @Override
    public List<Retention.Hold> holds(String subjectKind, UUID subjectId, int limit, int offset) {
        List<HoldRow> rows = jdbc.sql(HOLD + """
                         WHERE (CAST(:kind AS text) IS NULL OR h.subject_kind = :kind) AND (CAST(:subject AS uuid) IS NULL OR h.subject_id = :subject)
                         ORDER BY h.placed_at DESC, h.id DESC LIMIT :limit OFFSET :offset""")
                .param("kind", subjectKind).param("subject", subjectId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> hold(rs)).list();
        return withReleases(rows);
    }

    @Override
    public Optional<Retention.Hold> hold(UUID id) {
        List<HoldRow> rows = jdbc.sql(HOLD + " WHERE h.id = :id").param("id", id).query((rs, n) -> hold(rs)).list();
        return withReleases(rows).stream().findFirst();
    }

    @Override
    public Optional<Retention.RecordRetention> retentionOf(String kind, UUID id) {
        return jdbc.sql("SELECT * FROM record_retention(:kind, :id)").param("kind", kind).param("id", id)
                .query((rs, n) -> new Retention.RecordRetention(kind, id, rs.getString("retention_class"), rs.getString("standing"),
                        rs.getString("schedule_reference"), date(rs, "schedule_approved_on"), rs.getLong("holds_in_force"), rs.getBoolean("disposal_allowed")))
                .optional();
    }

    @Override
    public UUID place(String subjectKind, UUID subjectId, String reason) {
        return jdbc.sql("SELECT legal_hold_place(:kind, :subject, :reason)").param("kind", subjectKind).param("subject", subjectId).param("reason", reason)
                .query(UUID.class).single();
    }

    @Override
    public UUID requestRelease(UUID holdId, String reason) {
        return jdbc.sql("SELECT legal_hold_release_request(:hold, :reason)").param("hold", holdId).param("reason", reason).query(UUID.class).single();
    }

    @Override
    public void decideRelease(UUID releaseId, String outcome, String reason) {
        jdbc.sql("SELECT legal_hold_release_decide(:id, :outcome, :reason)").param("id", releaseId).param("outcome", outcome).param("reason", reason)
                .query((rs, n) -> 1).single();
    }

    private record HoldRow(UUID id, String subjectKind, UUID subjectId, String reason, UUID placedBy, String placedByName, Instant placedAt,
                           boolean inForce) {
    }

    private static HoldRow hold(ResultSet rs) throws SQLException {
        return new HoldRow(rs.getObject("id", UUID.class), rs.getString("subject_kind"), rs.getObject("subject_id", UUID.class), rs.getString("reason"),
                rs.getObject("placed_by", UUID.class), rs.getString("placed_by_name"), instant(rs, "placed_at"), rs.getBoolean("in_force"));
    }

    private List<Retention.Hold> withReleases(List<HoldRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Retention.Release>> releases = new LinkedHashMap<>();
        rows.forEach(r -> releases.put(r.id(), new ArrayList<>()));
        jdbc.sql("""
                        SELECT r.*, q.display_name AS requested_by_name, d.display_name AS decided_by_name
                          FROM legal_hold_release r JOIN app_user q ON q.id = r.requested_by LEFT JOIN app_user d ON d.id = r.decided_by
                         WHERE r.hold_id = ANY (CAST(:ids AS uuid[])) ORDER BY r.requested_at, r.id""")
                .param("ids", rows.stream().map(HoldRow::id).toArray(UUID[]::new))
                .query((rs, n) -> new Retention.Release(rs.getObject("id", UUID.class), rs.getObject("hold_id", UUID.class), rs.getString("reason"),
                        rs.getObject("requested_by", UUID.class), rs.getString("requested_by_name"), instant(rs, "requested_at"), rs.getString("status"),
                        rs.getObject("decided_by", UUID.class), rs.getString("decided_by_name"), instant(rs, "decided_at"), rs.getString("decision_reason")))
                .list().forEach(r -> releases.get(r.holdId()).add(r));
        return rows.stream().map(r -> new Retention.Hold(r.id(), r.subjectKind(), r.subjectId(), r.reason(), r.placedBy(), r.placedByName(), r.placedAt(),
                r.inForce(), releases.get(r.id()))).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
