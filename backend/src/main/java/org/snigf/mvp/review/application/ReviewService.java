// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review.application;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.application.ApplicationWorkflow;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.review.domain.Review;
import org.snigf.mvp.review.domain.ReviewRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Substantive review and simulated decision (MVP-05, E-15, ADR-035).
 * <ul>
 *   <li>A reviewer of the case's office claims a case under review, records a finding and a
 *       recommendation; the case is then pending decision.</li>
 *   <li>A decision authority of the office, who recommended nothing on the case, claims it and
 *       records APPROVED or REFUSED with grounds and the document versions cited (simulated,
 *       final), or returns it to review with a reason.</li>
 *   <li>No decision while an overlap or duplicate alert on the parcel is open (409
 *       {@code OPEN_ALERT}); none without grounds (422); none on a case in another state (409).</li>
 * </ul>
 * For a type with a procedure (ADR-042), an approval is recommended or decided only once every stage is
 * completed ({@code 409 PROCEDURE_INCOMPLETE}); a refusal stays possible. While an objection to its publication is
 * open, no recommendation (but a return for information) and no decision at all ({@code 409 OBJECTION_OPEN}).
 * Whoever recorded a stage of a case, or recorded or decided one of its objections, never claims it
 * ({@code 403 STAGE_RECORDER_CANNOT_REVIEW}, {@code 403 OBJECTION_HANDLER_CANNOT_REVIEW}).
 * Rounds (V36, ADR-043 step 5b): a reconsideration reopens an approved case in a new round; its assignments, steps and
 * decision belong to that round, and the latest round's decision is the one that counts. A decision authority who decided
 * the case in an earlier round never decides it again ({@code 403 EARLIER_DECIDER_CANNOT_DECIDE}), and whoever opened a
 * reconsideration never reviews or decides it ({@code 403 RECONSIDERATION_OPENER_CANNOT_REVIEW}).
 * The service refuses first, with a precise code, and audits each refusal; the database (V16, V32, V36)
 * checks the same rules again and computes nothing it is told.
 */
@Service
public class ReviewService {

    static final String ENTITY = "APPLICATION";
    public static final int PAGE_SIZE = 20;

    private final ApplicationWorkflow workflow;
    private final ReviewRepository reviews;
    private final AuditRecorder audit;
    private final DatabaseActor actor;

    public ReviewService(ApplicationWorkflow workflow, ReviewRepository reviews, AuditRecorder audit, DatabaseActor actor) {
        this.workflow = workflow;
        this.reviews = reviews;
        this.audit = audit;
        this.actor = actor;
    }

    /**
     * A case's review as staff see it: the state, who is assigned, the steps, the decision, open alerts, and what its
     * procedure still needs before an approval.
     */
    public record ReviewView(ApplicationWorkflow.CaseState state, List<Review.Assignment> assignments, List<Review.Step> steps,
                             Optional<Review.Decision> decision, int openAlerts, List<String> procedureGate, int round,
                             List<Review.Decision> earlierDecisions) {
    }

    @Transactional(readOnly = true)
    public List<Review.QueueEntry> queue(AuthenticatedUser staff, Review.Stage stage, boolean mine, int page) {
        actor.bind(staff.userId());
        if (page < 0 || page > 10_000) {
            throw new ReviewRefused(ReviewRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return reviews.queue(stage, mine, staff.userId(), PAGE_SIZE, page * PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public ReviewView view(AuthenticatedUser staff, UUID id) {
        ApplicationWorkflow.CaseState state = visible(staff, id);
        return new ReviewView(state, reviews.assignments(id), reviews.steps(id), reviews.decision(id), reviews.openAlerts(id),
                reviews.procedureGate(id), reviews.round(id), reviews.earlierDecisions(id));
    }

    @Transactional
    public ReviewView claim(AuthenticatedUser staff, UUID id, Review.Stage stage) {
        ApplicationWorkflow.CaseState state = visible(staff, id);
        String waiting = stage == Review.Stage.REVIEW ? "UNDER_REVIEW" : "PENDING_DECISION";
        if (!state.state().equals(waiting)) {
            throw refused(state, "INVALID_TRANSITION", ReviewRefused.Kind.CONFLICT);
        }
        if (reviews.assignments(id).stream().anyMatch(a -> a.stage() == stage)) {
            throw refused(state, "ALREADY_ASSIGNED", ReviewRefused.Kind.CONFLICT);
        }
        if (stage == Review.Stage.DECISION && reviews.steps(id).stream()
                .anyMatch(s -> s.kind().equals("RECOMMENDATION") && s.actorId().equals(staff.userId()))) {
            throw refused(state, "RECOMMENDER_CANNOT_DECIDE", ReviewRefused.Kind.FORBIDDEN);
        }
        guarded(state, () -> {
            reviews.claim(id, stage, staff.userId());
            return true;
        });
        audit.record(AuditEvent.of(AuditAction.CASE_CLAIMED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(state.reference())
                .withDetails(Map.of("stage", stage.name(), "office", state.officeCode(), "round", reviews.round(id))));
        return view(staff, id);
    }

    @Transactional
    public ReviewView recommend(AuthenticatedUser reviewer, UUID id, long expectedVersion, Review.Recommendation recommendation,
                                String finding) {
        ApplicationWorkflow.CaseState state = assigned(reviewer, id, Review.Stage.REVIEW, "UNDER_REVIEW", expectedVersion);
        if (recommendation == null) {
            throw refused(state, "RECOMMENDATION_REQUIRED", ReviewRefused.Kind.INVALID);
        }
        if (!Review.textValid(finding)) {
            throw refused(state, "FINDING_REQUIRED", ReviewRefused.Kind.INVALID);
        }
        if (recommendation != Review.Recommendation.RETURN_FOR_INFORMATION && reviews.objectionsOpen(id) > 0) {
            throw refused(state, "OBJECTION_OPEN", ReviewRefused.Kind.CONFLICT);
        }
        if (recommendation == Review.Recommendation.RECOMMEND_APPROVAL && !reviews.procedureGate(id).isEmpty()) {
            throw refused(state, "PROCEDURE_INCOMPLETE", ReviewRefused.Kind.CONFLICT);
        }
        guarded(state, () -> {
            reviews.addStep(id, "RECOMMENDATION", recommendation, finding.strip(), reviewer.userId());
            return workflow.move(reviewer.userId(), id, expectedVersion, "UNDER_REVIEW", "PENDING_DECISION",
                    recommendation.name());
        });
        audit.record(AuditEvent.of(AuditAction.REVIEW_RECOMMENDED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(state.reference())
                .withDetails(Map.of("recommendation", recommendation.name(), "office", state.officeCode(), "round", reviews.round(id))));
        return view(reviewer, id);
    }

    @Transactional
    public ReviewView returnToReview(AuthenticatedUser authority, UUID id, long expectedVersion, String reason) {
        ApplicationWorkflow.CaseState state = assigned(authority, id, Review.Stage.DECISION, "PENDING_DECISION", expectedVersion);
        if (!Review.textValid(reason)) {
            throw refused(state, "REASON_REQUIRED", ReviewRefused.Kind.INVALID);
        }
        guarded(state, () -> {
            reviews.addStep(id, "RETURN", null, reason.strip(), authority.userId());
            return workflow.move(authority.userId(), id, expectedVersion, "PENDING_DECISION", "UNDER_REVIEW", "RETURNED");
        });
        audit.record(AuditEvent.of(AuditAction.DECISION_RETURNED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(state.reference()).withReason(truncate(reason.strip()))
                .withDetails(Map.of("office", state.officeCode(), "round", reviews.round(id))));
        return view(authority, id);
    }

    @Transactional
    public ReviewView decide(AuthenticatedUser authority, UUID id, long expectedVersion, Review.Outcome outcome, String grounds,
                             List<UUID> cited) {
        ApplicationWorkflow.CaseState state = assigned(authority, id, Review.Stage.DECISION, "PENDING_DECISION", expectedVersion);
        if (outcome == null) {
            throw refused(state, "OUTCOME_REQUIRED", ReviewRefused.Kind.INVALID);
        }
        if (!Review.textValid(grounds)) {
            throw refused(state, "GROUNDS_REQUIRED", ReviewRefused.Kind.INVALID);
        }
        List<UUID> citations = cited == null ? List.of() : List.copyOf(new LinkedHashSet<>(cited));
        if (citations.isEmpty() || citations.size() > Review.MAX_CITED) {
            throw refused(state, "CITATIONS_REQUIRED", ReviewRefused.Kind.INVALID);
        }
        List<Review.Step> steps = reviews.steps(id);
        if (steps.stream().anyMatch(s -> s.kind().equals("RECOMMENDATION") && s.actorId().equals(authority.userId()))) {
            throw refused(state, "RECOMMENDER_CANNOT_DECIDE", ReviewRefused.Kind.FORBIDDEN);
        }
        if (reviews.openAlerts(id) > 0) {
            throw refused(state, "OPEN_ALERT", ReviewRefused.Kind.CONFLICT);
        }
        if (reviews.objectionsOpen(id) > 0) {
            throw refused(state, "OBJECTION_OPEN", ReviewRefused.Kind.CONFLICT);
        }
        if (outcome == Review.Outcome.APPROVED && !reviews.procedureGate(id).isEmpty()) {
            throw refused(state, "PROCEDURE_INCOMPLETE", ReviewRefused.Kind.CONFLICT);
        }
        int round = reviews.round(id);
        UUID follows = steps.stream().filter(s -> s.kind().equals("RECOMMENDATION") && s.round() == round).reduce((a, b) -> b)
                .map(Review.Step::id).orElseThrow(() -> refused(state, "RECOMMENDATION_MISSING", ReviewRefused.Kind.CONFLICT));
        guarded(state, () -> {
            reviews.decide(id, outcome, grounds.strip(), citations, follows, authority.userId());
            return workflow.move(authority.userId(), id, expectedVersion, "PENDING_DECISION", outcome.name(), "DECISION");
        });
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("outcome", outcome.name());
        facts.put("simulated", true);
        facts.put("cited", citations.size());
        facts.put("office", state.officeCode());
        facts.put("round", reviews.round(id));
        audit.record(AuditEvent.of(AuditAction.DECISION_RECORDED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY, id).withEntityRef(state.reference()).withReason(truncate(grounds.strip()))
                .withDetails(facts));
        return view(authority, id);
    }

    private ApplicationWorkflow.CaseState visible(AuthenticatedUser staff, UUID id) {
        return workflow.visible(staff.userId(), id)
                .orElseThrow(() -> new ReviewRefused(ReviewRefused.Kind.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    /** The case, assigned to this person at this stage, in the expected state and version. */
    private ApplicationWorkflow.CaseState assigned(AuthenticatedUser staff, UUID id, Review.Stage stage, String expectedState,
                                                   long expectedVersion) {
        ApplicationWorkflow.CaseState state = visible(staff, id);
        if (!state.state().equals(expectedState)) {
            throw refused(state, "INVALID_TRANSITION", ReviewRefused.Kind.CONFLICT);
        }
        if (reviews.assignments(id).stream().noneMatch(a -> a.stage() == stage && a.assigneeId().equals(staff.userId()))) {
            throw refused(state, "NOT_ASSIGNED", ReviewRefused.Kind.FORBIDDEN);
        }
        if (state.version() != expectedVersion) {
            throw new ReviewRefused(ReviewRefused.Kind.CONFLICT, "STALE_VERSION");
        }
        return state;
    }

    /** Runs the record-and-move; the database's disagreement becomes a precise refusal. */
    private void guarded(ApplicationWorkflow.CaseState state, java.util.function.BooleanSupplier step) {
        boolean moved;
        try {
            moved = step.getAsBoolean();
        } catch (DataAccessException e) {
            switch (sqlState(e)) {
                case "23505" -> throw refused(state, "ALREADY_ASSIGNED", ReviewRefused.Kind.CONFLICT);
                case "42501" -> throw refused(state, "REVIEW_REFUSED", ReviewRefused.Kind.FORBIDDEN);
                case "40001" -> throw new ReviewRefused(ReviewRefused.Kind.CONFLICT, "STALE_VERSION");
                case "23001" -> throw refused(state, "INVALID_TRANSITION", ReviewRefused.Kind.CONFLICT);
                case "23514" -> throw refused(state, "REVIEW_INVALID", ReviewRefused.Kind.INVALID);
                case "PC001" -> {
                    String code = procedureCode(e);
                    boolean conflict = code.equals("PROCEDURE_INCOMPLETE") || code.equals("OBJECTION_OPEN") || code.equals("PARTIES_INCOMPLETE");
                    throw refused(state, code, conflict ? ReviewRefused.Kind.CONFLICT : ReviewRefused.Kind.FORBIDDEN);
                }
                default -> throw e;
            }
        }
        if (!moved) {
            throw new ReviewRefused(ReviewRefused.Kind.CONFLICT, "STALE_VERSION");
        }
    }

    private ReviewRefused refused(ApplicationWorkflow.CaseState state, String code, ReviewRefused.Kind kind) {
        audit.recordIndependently(AuditEvent.of(AuditAction.REVIEW_REJECTED, AuditOutcome.FAILED)
                .withEntity(ENTITY, state.id()).withEntityRef(state.reference()).withReason(code)
                .withDetails(Map.of("code", code, "state", state.state())));
        return new ReviewRefused(kind, code);
    }

    private static String truncate(String text) {
        return text.length() > 1000 ? text.substring(0, 999) + "…" : text;
    }

    /** The reason a procedure trigger gave (V32): the first word of its message. */
    private static String procedureCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "PC001".equals(sql.getSQLState())) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(": ([A-Z][A-Z0-9_]{2,63})\\b").matcher(String.valueOf(sql.getMessage()));
                return m.find() ? m.group(1) : "PROCEDURE_REFUSED";
            }
        }
        return "PROCEDURE_REFUSED";
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }
}
