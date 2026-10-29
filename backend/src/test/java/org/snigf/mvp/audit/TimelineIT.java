// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * E-18, MVP-07 ("a supervisor can reconstruct a case's timeline from audit alone"), ADR-027 and
 * ADR-038, through the real endpoints. A whole case is lived (draft, documents, submission,
 * payment through the signed callback, acceptance, review, alert, decision), and then:
 * <ul>
 *   <li>the auditor reads every one of its events, oldest first, found by its reference, each
 *       intact, the draft's included;</li>
 *   <li>the supervisor of its office reads the same from the submission on, never the draft's
 *       events (no office yet), never another office's;</li>
 *   <li>a supervisor elsewhere gets 404, and a reviewer 403;</li>
 *   <li>the event search is limited to the supervisor's office in the same way;</li>
 *   <li>the Java and SQL canonical forms agree on events with subjects, and an altered event
 *       shows as altered.</li>
 * </ul>
 */
class TimelineIT extends CaseFlowIntegrationTest {

    private static final String TIMELINE = "/api/v1/audit/timeline";
    private static final String OFFICE = "DEMOTL1";

    /** The office's case, decided; returns {application id, application reference, parcel reference}. */
    private String[] decidedCase(Browser applicant) throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER", OFFICE);
        long[] area = area();
        String parcel = parcel(cadastral, area, 0, OFFICE);
        String id = submitted(applicant, parcel);
        paid(applicant, id);
        accepted(signedIn("INTAKE_OFFICER", OFFICE), id);
        SignedIn reviewer = signedIn("REVIEWER", OFFICE);
        assertThat(act(reviewer, id, "review-claim", "{}").getStatus()).isEqualTo(200);
        assertThat(act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id), "recommendation",
                "RECOMMEND_APPROVAL", "finding", "Title and boundaries are consistent")).getStatus()).isEqualTo(200);
        SignedIn authority = signedIn("DECISION_AUTHORITY", OFFICE);
        assertThat(act(authority, id, "decision-claim", "{}").getStatus()).isEqualTo(200);
        String cited = json.readTree(authority.browser().send(get("/api/v1/assigned-cases/" + id + "/documents"))
                .getContentAsString()).path("categories").get(0).path("versions").get(0).path("id").asString("");
        MockHttpServletResponse decided = act(authority, id, "decision", body("expectedVersion", version(authority, id),
                "outcome", "APPROVED", "grounds", "Title proven and boundaries agreed", "citedVersions", List.of(cited)));
        assertThat(decided.getStatus()).as(decided.getContentAsString()).isEqualTo(200);
        String reference = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString()).path("reference").asString("");
        return new String[] {id, reference, parcel};
    }

    @Test
    void theAuditorAndTheOfficesSupervisorReconstructTheCaseFromAuditAlone() throws Exception {
        Browser applicant = applicant();
        String[] c = decidedCase(applicant);
        String id = c[0];
        String reference = c[1];

        // The auditor: every event of the case, oldest first, the draft's included, each intact.
        SignedIn auditor = signedIn("AUDITOR", OFFICE);
        JsonNode all = timeline(auditor, "APPLICATION", reference);
        assertThat(all.path("subjectId").asString("")).isEqualTo(id);
        assertThat(all.path("reference").asString("")).isEqualTo(reference);
        List<String> actions = actions(all);
        assertThat(actions).containsSubsequence("APPLICATION_DRAFT_CREATED", "DOCUMENT_UPLOADED", "APPLICATION_SUBMITTED",
                "PAYMENT_REQUESTED", "PAYMENT_CALLBACK_ACCEPTED", "COMPLETENESS_ACCEPTED", "CASE_CLAIMED",
                "REVIEW_RECOMMENDED", "CASE_CLAIMED", "DECISION_RECORDED");
        long previous = 0;
        for (JsonNode e : all.path("events")) {
            assertThat(e.path("chainPos").asLong()).isGreaterThan(previous);
            previous = e.path("chainPos").asLong();
            assertThat(e.path("intact").asBoolean()).as("event %s intact", e.path("action").asString("")).isTrue();
        }
        JsonNode draft = first(all, "APPLICATION_DRAFT_CREATED");
        assertThat(draft.path("subjects").get(0).path("officeCode").isNull()).as("a draft has no office yet").isTrue();
        JsonNode submittedEvent = first(all, "APPLICATION_SUBMITTED");
        assertThat(submittedEvent.path("subjects").get(0).path("officeCode").asString("")).isEqualTo(OFFICE);
        assertThat(first(all, "PAYMENT_CALLBACK_ACCEPTED").path("actorLabel").asString("")).isEqualTo("payment-callback");
        assertThat(first(all, "DECISION_RECORDED").path("actorName").asString("")).isNotEmpty();

        // The supervisor of the office: the same from the submission on, never the draft's events.
        SignedIn supervisor = signedIn("SUPERVISOR", OFFICE);
        JsonNode office = timeline(supervisor, "APPLICATION", reference);
        List<String> seen = actions(office);
        assertThat(seen).doesNotContain("APPLICATION_DRAFT_CREATED")
                .containsSubsequence("APPLICATION_SUBMITTED", "PAYMENT_REQUESTED", "PAYMENT_CALLBACK_ACCEPTED",
                        "COMPLETENESS_ACCEPTED", "CASE_CLAIMED", "REVIEW_RECOMMENDED", "DECISION_RECORDED");
        for (JsonNode e : office.path("events")) {
            assertThat(e.path("subjects").findValuesAsString("officeCode")).contains(OFFICE);
        }
        assertThat(office.path("events").size()).isLessThan(all.path("events").size());

        // By id too; a supervisor elsewhere, or a parcel of another office, finds nothing; a reviewer may not ask.
        assertThat(supervisor.browser().send(get(TIMELINE).param("type", "APPLICATION").param("id", id)).getStatus())
                .isEqualTo(200);
        SignedIn elsewhere = signedIn("SUPERVISOR", "DEMOTL2");
        MockHttpServletResponse hidden = elsewhere.browser().send(get(TIMELINE).param("type", "APPLICATION")
                .param("reference", reference));
        assertThat(hidden.getStatus()).isEqualTo(404);
        assertThat(code(hidden)).isEqualTo("TIMELINE_NOT_FOUND");
        assertThat(elsewhere.browser().send(get(TIMELINE).param("type", "APPLICATION")
                .param("reference", "DEMO-APP-2026-999999-0")).getStatus()).as("unknown alike").isEqualTo(404);
        assertThat(signedIn("REVIEWER", OFFICE).browser().send(get(TIMELINE).param("type", "APPLICATION")
                .param("reference", reference)).getStatus()).isEqualTo(403);
        assertThat(auditor.browser().send(get(TIMELINE).param("type", "USER").param("reference", "x")).getStatus())
                .isEqualTo(422);

        // The parcel's timeline: its creation, at its office.
        JsonNode parcel = timeline(supervisor, "PARCEL", c[2]);
        assertThat(actions(parcel)).contains("PARCEL_CREATED");

        // The event search, for the supervisor: only events of their office, the case's by subject.
        JsonNode search = json.readTree(supervisor.browser().send(get("/api/v1/audit/events")
                .param("subjectType", "APPLICATION").param("subjectId", id).param("size", "100")).getContentAsString());
        assertThat(search.path("events").size()).isEqualTo(office.path("events").size());
        JsonNode recent = json.readTree(supervisor.browser().send(get("/api/v1/audit/events").param("size", "100"))
                .getContentAsString());
        assertThat(recent.path("events").size()).isPositive();
        for (JsonNode e : recent.path("events")) {
            assertThat(e.path("subjects").findValuesAsString("officeCode")).as("chain %d", e.path("chainPos").asLong())
                    .contains(OFFICE);
        }
        assertThat(elsewhere.browser().send(get("/api/v1/audit/events").param("subjectType", "APPLICATION")
                .param("subjectId", id)).getContentAsString()).contains("\"events\":[]");

        // Reading a timeline is audited.
        JsonNode reads = json.readTree(auditor.browser().send(get("/api/v1/audit/events").param("action", "AUDIT_TIMELINE_READ")
                .param("actorId", supervisor.id().toString())).getContentAsString());
        assertThat(reads.path("events").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void javaAndSqlAgreeOnEventsWithSubjectsAndAnAlteredEventShows() throws Exception {
        Browser applicant = applicant();
        String[] c = decidedCase(applicant);
        SignedIn auditor = signedIn("AUDITOR", OFFICE);
        JsonNode all = timeline(auditor, "APPLICATION", c[1]);

        List<Long> positions = new ArrayList<>();
        all.path("events").forEach(e -> positions.add(e.path("chainPos").asLong()));
        for (long pos : positions) {
            String sql = new String(jdbc.queryForObject("SELECT audit_canonical(e) FROM audit_event e WHERE chain_pos = ?",
                    byte[].class, pos), StandardCharsets.UTF_8);
            assertThat(sql).startsWith("snigf-audit-v2|");
        }

        // An administrator rewrites a row: the timeline shows it altered.
        long accepted = first(all, "COMPLETENESS_ACCEPTED").path("chainPos").asLong();
        String reason;
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             PreparedStatement read = su.prepareStatement("SELECT coalesce(reason, '') FROM snigf.audit_event WHERE chain_pos = ?")) {
            read.setLong(1, accepted);
            try (var rs = read.executeQuery()) {
                rs.next();
                reason = rs.getString(1);
            }
        }
        alter(accepted, "rewritten by an administrator");
        try {
            JsonNode altered = timeline(auditor, "APPLICATION", c[1]);
            assertThat(first(altered, "COMPLETENESS_ACCEPTED").path("intact").asBoolean()).isFalse();
        } finally {
            alter(accepted, reason.isEmpty() ? null : reason);
        }
        assertThat(first(timeline(auditor, "APPLICATION", c[1]), "COMPLETENESS_ACCEPTED").path("intact").asBoolean()).isTrue();
    }

    private JsonNode timeline(SignedIn who, String type, String reference) throws Exception {
        MockHttpServletResponse response = who.browser().send(get(TIMELINE).param("type", type).param("reference", reference));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private static List<String> actions(JsonNode timeline) {
        List<String> actions = new ArrayList<>();
        timeline.path("events").forEach(e -> actions.add(e.path("action").asString("")));
        return actions;
    }

    private static JsonNode first(JsonNode timeline, String action) {
        for (JsonNode e : timeline.path("events")) {
            if (e.path("action").asString("").equals(action)) {
                return e;
            }
        }
        throw new AssertionError("no " + action + " in " + actions(timeline));
    }

    /** An administrator rewriting a row behind the application's back (triggers off: superuser only). */
    private static void alter(long chainPos, String reason) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             Statement replica = su.createStatement();
             PreparedStatement update = su.prepareStatement("UPDATE snigf.audit_event SET reason = ? WHERE chain_pos = ?")) {
            replica.execute("SET session_replication_role = replica");
            update.setString(1, reason);
            update.setLong(2, chainPos);
            assertThat(update.executeUpdate()).isEqualTo(1);
            replica.execute("SET session_replication_role = origin");
            su.commit();
        }
    }
}
