// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.snigf.mvp.reporting.domain.StageTargets;
import org.snigf.mvp.reporting.domain.Workload;
import org.snigf.mvp.reporting.domain.WorkloadRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workload through the database's report functions (V17), which check the actor's
 * {@code report.workload} at the office and compute every time from the transition table.
 * Intervals come back as seconds.
 */
@Repository
class JdbcWorkloadRepository implements WorkloadRepository {

    private static final String OPEN = "workload_open_cases(:office, CAST(:targets AS jsonb))";
    private static final Comparator<String> LIFECYCLE = Comparator.comparingInt(StageTargets.SUBMITTED_STATES::indexOf);

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcWorkloadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Workload.StateCount> counts(String officeCode) {
        return jdbc.sql("SELECT state, cases FROM workload_counts(:office)").param("office", officeCode)
                .query((rs, n) -> new Workload.StateCount(rs.getString("state"), rs.getLong("cases"))).list().stream()
                .sorted(Comparator.comparing(Workload.StateCount::state, LIFECYCLE)).toList();
    }

    @Override
    public List<Workload.OpenInState> openByState(String officeCode, StageTargets targets) {
        return jdbc.sql("SELECT state, count(*) AS open, count(*) FILTER (WHERE overdue) AS overdue FROM " + OPEN
                        + " GROUP BY state")
                .param("office", officeCode).param("targets", json.writeValueAsString(targets.inSeconds()))
                .query((rs, n) -> new Workload.OpenInState(rs.getString("state"), rs.getLong("open"), rs.getLong("overdue")))
                .list().stream().sorted(Comparator.comparing(Workload.OpenInState::state, LIFECYCLE)).toList();
    }

    @Override
    public List<Workload.AssigneeLoad> assignees(String officeCode, StageTargets targets) {
        return jdbc.sql("""
                        SELECT assignee_id, assignee_name, state, count(*) AS cases, count(*) FILTER (WHERE overdue) AS overdue
                          FROM %s
                         WHERE assignee_id IS NOT NULL
                         GROUP BY assignee_id, assignee_name, state
                         ORDER BY count(*) DESC, assignee_name, state""".formatted(OPEN))
                .param("office", officeCode).param("targets", json.writeValueAsString(targets.inSeconds()))
                .query((rs, n) -> new Workload.AssigneeLoad(rs.getObject("assignee_id", UUID.class),
                        rs.getString("assignee_name"), rs.getString("state"), rs.getLong("cases"), rs.getLong("overdue")))
                .list();
    }

    @Override
    public List<Workload.OpenCase> openCases(String officeCode, StageTargets targets, String state, boolean overdueOnly,
                                             int limit, int offset) {
        return jdbc.sql("""
                        SELECT application_id, reference, parcel_reference, state, since,
                               extract(epoch FROM waited) AS waited_s, extract(epoch FROM target) AS target_s,
                               overdue, assignee_name
                          FROM %s
                         WHERE (CAST(:state AS text) IS NULL OR state = :state) AND (NOT :overdueOnly OR overdue)
                         ORDER BY since, application_id LIMIT :limit OFFSET :offset""".formatted(OPEN))
                .param("office", officeCode).param("targets", json.writeValueAsString(targets.inSeconds()))
                .param("state", state).param("overdueOnly", overdueOnly).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Workload.OpenCase(rs.getObject("application_id", UUID.class), rs.getString("reference"),
                        rs.getString("parcel_reference"), rs.getString("state"), instant(rs, "since"),
                        seconds(rs, "waited_s"), seconds(rs, "target_s"), rs.getBoolean("overdue"),
                        rs.getString("assignee_name")))
                .list();
    }

    @Override
    public List<Workload.StageDuration> durations(String officeCode, StageTargets targets, Instant from) {
        return jdbc.sql("""
                        SELECT state, stays, extract(epoch FROM average) AS average_s, extract(epoch FROM median) AS median_s,
                               extract(epoch FROM longest) AS longest_s, over_target
                          FROM workload_completed_stays(:office, CAST(:targets AS jsonb), :from)""")
                .param("office", officeCode).param("targets", json.writeValueAsString(targets.inSeconds()))
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .query((rs, n) -> new Workload.StageDuration(rs.getString("state"), rs.getLong("stays"),
                        seconds(rs, "average_s"), seconds(rs, "median_s"), seconds(rs, "longest_s"), rs.getLong("over_target")))
                .list().stream().sorted(Comparator.comparing(Workload.StageDuration::state, LIFECYCLE)).toList();
    }

    private static Duration seconds(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : Duration.ofMillis(value.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).longValue());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
