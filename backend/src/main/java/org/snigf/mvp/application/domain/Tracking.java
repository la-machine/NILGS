// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.time.Instant;
import java.util.List;

/**
 * What an applicant follows of their application (MVP-06: "current state, date entered,
 * public-safe message, pending requests; internal notes and reviewer identities are hidden").
 * Built from the records staff see, keeping only what is meant for the applicant:
 * <ul>
 *   <li>each step says who had it in hand (the applicant, the office, the system), never which
 *       person, and never the reason recorded with a state change (a recommendation, a return to
 *       review: internal);</li>
 *   <li>messages are only those written to the applicant (an information request) or by them
 *       (their response);</li>
 *   <li>the date the current state was entered comes from the history, never from a field a
 *       person writes.</li>
 * </ul>
 *
 * @param since          when the application entered its current state (its creation, for a draft)
 * @param waitingOn      who must act next; null once the application has ended
 * @param pendingRequest the information request the applicant must answer; null otherwise
 */
public record Tracking(ApplicationState state, Instant since, Party waitingOn, PendingRequest pendingRequest,
                       List<Step> history, List<CompletenessStep> completeness) {

    /**
     * A stage of the case's procedure as its applicant sees it (ADR-042): the stage, {@code NOT_STARTED}, {@code COMPLETED},
     * {@code NOT_SATISFIED}, {@code IN_PROGRESS} (a publication whose objection period is open) or {@code OUTDATED} (a record
     * that no longer holds, to be made again), and when its latest record was made; never the officer, the finding, the
     * evidence, a notice's objections or an objector.
     */
    public record StageProgress(String stage, String status, Instant at) {
    }

    /**
     * Work the office asks to carry out again (ADR-042, decision 5 of 13 Nov 2026): the stages, the publication anew
     * after them, what the applicant must do (null when nothing), since when, and whether any of it is still to do.
     * Never the objection that led to it, its number, the objector, the statement or the grounds.
     */
    public record ProcedureInstruction(List<String> stages, String action, Instant at, boolean pending) {
    }

    /**
     * The title the case was registered under in the DEMO register (ADR-042 §4, step 5): its number and when. A case is
     * not a title: the case stays APPROVED. Never the holders, the inscription or the conservator.
     */
    public record Registration(String titleNumber, Instant registeredAt, String kind) {
    }

    /**
     * A person of the case as its applicant reads them (ADR-043): their reference, roles and name, and where the office is
     * with their identity ({@code DECLARED}, {@code RECORDED}, {@code MATCHES_DOCUMENT}, {@code DOES_NOT_MATCH}). Always DEMO,
     * never independently verified; never the officer or a check's note.
     */
    public record CasePerson(String ref, List<String> roles, String fullName, String status) {
    }

    /**
     * A reconsideration of the approved case as its applicant reads it (ADR-043 step 5b, D41): the round, the stages carried
     * out again, since when, and whether the new decision is made. Never the justification, the objections, the objectors
     * or who opened it.
     */
    public record Reconsideration(int round, List<String> stages, Instant openedAt, boolean decided) {
    }

    /** Who had the application in hand at a step: no person is ever named to the applicant. */
    public enum Party { APPLICANT, OFFICE, SYSTEM }

    public record Step(ApplicationState from, ApplicationState to, Party by, Instant at) {
    }

    /**
     * @param message the office's message to the applicant (information request) or the applicant's own (response)
     */
    public record CompletenessStep(CompletenessRecord.Kind kind, List<String> missing, List<String> requested,
                                   String message, Party by, Instant at, List<CompletenessRecord.Replacement> replacements) {
    }

    /**
     * What the office asked for and when; the applicant answers with files in these categories. {@code replacements}:
     * the documents to replace, and why; each takes one new version.
     */
    public record PendingRequest(List<String> categories, String message, Instant since, List<CompletenessRecord.Replacement> replacements) {
    }

    public Tracking {
        history = List.copyOf(history);
        completeness = List.copyOf(completeness);
    }

    public static Tracking of(Application application, List<Application.Transition> transitions,
                              List<CompletenessRecord> records) {
        List<Step> steps = transitions.stream()
                .map(t -> new Step(t.from(), t.to(), party(application, t), t.at())).toList();
        List<CompletenessStep> review = records.stream()
                .map(r -> new CompletenessStep(r.kind(), r.missing(), r.requested(), publicMessage(r), party(r), r.at(), r.replacements()))
                .toList();
        Instant since = transitions.isEmpty() ? application.createdAt() : transitions.getLast().at();
        PendingRequest pending = null;
        if (application.state() == ApplicationState.INFO_REQUESTED) {
            pending = records.stream().filter(r -> r.kind() == CompletenessRecord.Kind.INFO_REQUESTED)
                    .reduce((first, second) -> second)
                    .map(r -> new PendingRequest(r.requested(), r.message(), r.at(), r.replacements())).orElse(null);
        }
        return new Tracking(application.state(), since, waitingOn(application.state()), pending, steps, review);
    }

    /** Who must act next in a state; null once it has ended. */
    public static Party waitingOn(ApplicationState state) {
        return switch (state) {
            case DRAFT, INFO_REQUESTED -> Party.APPLICANT;
            case SUBMITTED, COMPLETENESS_REVIEW, UNDER_REVIEW, PENDING_DECISION -> Party.OFFICE;
            case APPROVED, REFUSED, WITHDRAWN -> null;
        };
    }

    private static Party party(Application application, Application.Transition t) {
        if (t.actorId() == null) {
            return Party.SYSTEM;
        }
        return application.ownedBy(t.actorId()) ? Party.APPLICANT : Party.OFFICE;
    }

    private static Party party(CompletenessRecord r) {
        return switch (r.kind()) {
            case AUTOMATIC -> Party.SYSTEM;
            case RESPONSE -> Party.APPLICANT;
            case COMPLETE, INFO_REQUESTED -> Party.OFFICE;
        };
    }

    private static String publicMessage(CompletenessRecord r) {
        return r.kind() == CompletenessRecord.Kind.INFO_REQUESTED || r.kind() == CompletenessRecord.Kind.RESPONSE
                ? r.message() : null;
    }
}
