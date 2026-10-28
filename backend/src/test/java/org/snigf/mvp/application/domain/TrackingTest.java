// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the applicant follows (MVP-06): the state, when it was entered, who acts next, the pending
 * request; never a person's name nor the reason recorded with a state change.
 */
class TrackingTest {

    static final UUID APPLICANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    static final UUID INTAKE = UUID.fromString("00000000-0000-7000-8000-000000000002");
    static final UUID REVIEWER = UUID.fromString("00000000-0000-7000-8000-000000000003");
    static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    void stepsSayWhoHadItInHandNeverWhichPersonNorWhy() {
        Application a = application(ApplicationState.PENDING_DECISION);
        List<Application.Transition> history = List.of(
                new Application.Transition(ApplicationState.DRAFT, ApplicationState.SUBMITTED, APPLICANT, "Demo Applicant", null, T0),
                new Application.Transition(ApplicationState.SUBMITTED, ApplicationState.COMPLETENESS_REVIEW, null, null, null, at(1)),
                new Application.Transition(ApplicationState.COMPLETENESS_REVIEW, ApplicationState.UNDER_REVIEW, INTAKE, "Ines Intake", null, at(2)),
                new Application.Transition(ApplicationState.UNDER_REVIEW, ApplicationState.PENDING_DECISION, REVIEWER, "Rita Reviewer",
                        "RECOMMEND_REFUSAL", at(3)));

        Tracking t = Tracking.of(a, history, List.of());

        assertThat(t.history()).extracting(Tracking.Step::by).containsExactly(
                Tracking.Party.APPLICANT, Tracking.Party.SYSTEM, Tracking.Party.OFFICE, Tracking.Party.OFFICE);
        assertThat(t.toString()).doesNotContain("Rita Reviewer", "Ines Intake", "RECOMMEND_REFUSAL");
        assertThat(t.since()).as("entered the current state with the last move").isEqualTo(at(3));
        assertThat(t.waitingOn()).isEqualTo(Tracking.Party.OFFICE);
        assertThat(t.pendingRequest()).isNull();
    }

    @Test
    void anInformationRequestIsPendingWithTheOfficesMessageAndTheApplicantActsNext() {
        Application a = application(ApplicationState.INFO_REQUESTED);
        List<CompletenessRecord> records = List.of(
                new CompletenessRecord(UUID.randomUUID(), CompletenessRecord.Kind.AUTOMATIC, List.of(), null, null, null, at(1)),
                new CompletenessRecord(UUID.randomUUID(), CompletenessRecord.Kind.INFO_REQUESTED, List.of(),
                        List.of("DEMO_IDENTITY"), "The identity document is unreadable", "Ines Intake", at(2)));
        List<Application.Transition> history = List.of(
                new Application.Transition(ApplicationState.COMPLETENESS_REVIEW, ApplicationState.INFO_REQUESTED, INTAKE,
                        "Ines Intake", "The identity document is unreadable", at(2)));

        Tracking t = Tracking.of(a, history, records);

        assertThat(t.waitingOn()).isEqualTo(Tracking.Party.APPLICANT);
        assertThat(t.pendingRequest()).isEqualTo(new Tracking.PendingRequest(List.of("DEMO_IDENTITY"),
                "The identity document is unreadable", at(2)));
        assertThat(t.completeness()).extracting(Tracking.CompletenessStep::by)
                .containsExactly(Tracking.Party.SYSTEM, Tracking.Party.OFFICE);
        assertThat(t.toString()).doesNotContain("Ines Intake");
    }

    @Test
    void onlyMessagesToOrFromTheApplicantAreKept() {
        Application a = application(ApplicationState.UNDER_REVIEW);
        List<CompletenessRecord> records = List.of(
                new CompletenessRecord(UUID.randomUUID(), CompletenessRecord.Kind.RESPONSE, List.of(), null, "Here it is", "Demo Applicant", at(1)),
                new CompletenessRecord(UUID.randomUUID(), CompletenessRecord.Kind.COMPLETE, List.of(), null, "never shown", "Ines Intake", at(2)));

        Tracking t = Tracking.of(a, List.of(), records);

        assertThat(t.completeness()).extracting(Tracking.CompletenessStep::message).containsExactly("Here it is", null);
    }

    @Test
    void aDraftDatesFromItsCreationAndAnEndedApplicationWaitsOnNobody() {
        assertThat(Tracking.of(application(ApplicationState.DRAFT), List.of(), List.of()).since()).isEqualTo(T0);
        assertThat(Tracking.waitingOn(ApplicationState.DRAFT)).isEqualTo(Tracking.Party.APPLICANT);
        for (ApplicationState ended : List.of(ApplicationState.APPROVED, ApplicationState.REFUSED, ApplicationState.WITHDRAWN)) {
            assertThat(Tracking.waitingOn(ended)).as(ended.name()).isNull();
        }
    }

    private static Application application(ApplicationState state) {
        return new Application(UUID.randomUUID(), state == ApplicationState.DRAFT ? null : "DEMO-APP-2026-000001-X", APPLICANT,
                "DEMO_REGISTRATION", UUID.randomUUID(), state, null, null, Map.of(), T0, T0, null, 1);
    }

    private static Instant at(int hours) {
        return T0.plusSeconds(hours * 3600L);
    }
}
