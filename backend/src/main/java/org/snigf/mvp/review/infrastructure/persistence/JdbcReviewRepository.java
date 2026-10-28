// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.review.domain.Review;
import org.snigf.mvp.review.domain.ReviewRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Review data under row-level security (V16). The queue reads the applications the actor may see
 * as an assignee: the cases assigned to them, and those of their office waiting at their stage.
 */
@Repository
class JdbcReviewRepository implements ReviewRepository {

    private final JdbcClient jdbc;

    JdbcReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Review.Assignment> assignments(UUID applicationId) {
        return jdbc.sql("""
                        SELECT c.stage, c.assignee_id, u.display_name, c.at
                          FROM case_assignment c JOIN app_user u ON u.id = c.assignee_id
                         WHERE c.application_id = :id ORDER BY c.at""")
                .param("id", applicationId)
                .query((rs, n) -> new Review.Assignment(Review.Stage.valueOf(rs.getString(1)), rs.getObject(2, UUID.class),
                        rs.getString(3), instant(rs, "at")))
                .list();
    }

    @Override
    public List<Review.Step> steps(UUID applicationId) {
        return jdbc.sql("""
                        SELECT s.id, s.kind, s.recommendation, s.text, s.actor_id, u.display_name, s.at
                          FROM review_step s JOIN app_user u ON u.id = s.actor_id
                         WHERE s.application_id = :id ORDER BY s.at, s.id""")
                .param("id", applicationId)
                .query((rs, n) -> new Review.Step(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getString("recommendation") == null ? null : Review.Recommendation.valueOf(rs.getString("recommendation")),
                        rs.getString("text"), rs.getObject("actor_id", UUID.class), rs.getString("display_name"), instant(rs, "at")))
                .list();
    }

    @Override
    public Optional<Review.Decision> decision(UUID applicationId) {
        return jdbc.sql("""
                        SELECT d.id, d.outcome, d.grounds, d.cited_versions, d.simulated, u.display_name, d.at
                          FROM decision d JOIN app_user u ON u.id = d.decided_by
                         WHERE d.application_id = :id""")
                .param("id", applicationId)
                .query((rs, n) -> new Review.Decision(rs.getObject("id", UUID.class), Review.Outcome.valueOf(rs.getString("outcome")),
                        rs.getString("grounds"), Arrays.asList((UUID[]) rs.getArray("cited_versions").getArray()),
                        rs.getBoolean("simulated"), rs.getString("display_name"), instant(rs, "at")))
                .optional();
    }

    @Override
    public int openAlerts(UUID applicationId) {
        return jdbc.sql("SELECT application_open_alerts(:id)").param("id", applicationId).query(Integer.class).single();
    }

    @Override
    public void claim(UUID applicationId, Review.Stage stage, UUID actor) {
        jdbc.sql("""
                        INSERT INTO case_assignment (application_id, stage, assignee_id, assigned_by)
                        VALUES (:id, :stage, :actor, :actor)""")
                .param("id", applicationId).param("stage", stage.name()).param("actor", actor).update();
    }

    @Override
    public UUID addStep(UUID applicationId, String kind, Review.Recommendation recommendation, String text, UUID actor) {
        return jdbc.sql("""
                        INSERT INTO review_step (application_id, kind, recommendation, text, actor_id)
                        VALUES (:id, :kind, :recommendation, :text, :actor) RETURNING id""")
                .param("id", applicationId).param("kind", kind)
                .param("recommendation", recommendation == null ? null : recommendation.name())
                .param("text", text).param("actor", actor)
                .query(UUID.class).single();
    }

    @Override
    public void decide(UUID applicationId, Review.Outcome outcome, String grounds, List<UUID> cited, UUID followsStep,
                       UUID actor) {
        jdbc.sql("""
                        INSERT INTO decision (application_id, outcome, grounds, cited_versions, follows_step_id, decided_by)
                        VALUES (:id, :outcome, :grounds, CAST(:cited AS uuid[]), :step, :actor)""")
                .param("id", applicationId).param("outcome", outcome.name()).param("grounds", grounds)
                .param("cited", cited.toArray(UUID[]::new)).param("step", followsStep).param("actor", actor)
                .update();
    }

    @Override
    public List<Review.QueueEntry> queue(Review.Stage stage, boolean mine, UUID actor, int limit, int offset) {
        String waiting = stage == Review.Stage.REVIEW ? "UNDER_REVIEW" : "PENDING_DECISION";
        return jdbc.sql("""
                        SELECT a.id, a.reference, a.state, a.parcel_reference, o.code AS office_code,
                               u.display_name AS applicant_name, a.updated_at, au.display_name AS assignee_name,
                               c.assignee_id = :actor AS mine
                          FROM application a
                          JOIN office o ON o.id = a.office_id
                          JOIN app_user u ON u.id = a.applicant_id
                          LEFT JOIN case_assignment c ON c.application_id = a.id AND c.stage = :stage
                          LEFT JOIN app_user au ON au.id = c.assignee_id
                         WHERE CASE WHEN :mine THEN c.assignee_id = :actor
                                    ELSE a.state = :waiting AND c.id IS NULL END
                         ORDER BY a.updated_at, a.id LIMIT :limit OFFSET :offset""")
                .param("actor", actor).param("stage", stage.name()).param("mine", mine).param("waiting", waiting)
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> new Review.QueueEntry(rs.getObject("id", UUID.class), rs.getString("reference"),
                        rs.getString("state"), rs.getString("parcel_reference"), rs.getString("office_code"),
                        rs.getString("applicant_name"), instant(rs, "updated_at"), rs.getString("assignee_name"),
                        rs.getBoolean("mine")))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
