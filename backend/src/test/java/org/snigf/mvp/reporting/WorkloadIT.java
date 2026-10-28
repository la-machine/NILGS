// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MVP-06 / E-16, the supervisor's side, through the real endpoints: an office's counts per state,
 * its cases under way with the time in their state against the DEMO target and who holds them,
 * and the stage durations, all derived from the transition table (Baseline acceptance). Each test
 * works at an office of its own, so the cases of other tests never count.
 */
class WorkloadIT extends CaseFlowIntegrationTest {

    static final String WORKLOAD = "/api/v1/reports/workload";

    @Test
    void aSupervisorSeesCountsTimeInStageWhoHoldsEachCaseAndStageDurations() throws Exception {
        String office = "DEMOWLA";
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER", office);
        SignedIn intake = signedIn("INTAKE_OFFICER", office);
        SignedIn reviewer = signedIn("REVIEWER", office);
        SignedIn authority = signedIn("DECISION_AUTHORITY", office);
        Browser applicant = applicant();
        long[] area = area();

        // A: waiting for the completeness review. B: under review, claimed. C: decided.
        String waiting = submitted(applicant, parcel(cadastral, area, 0, office));
        String claimed = submitted(applicant, parcel(cadastral, area, 100, office));
        String decided = submitted(applicant, parcel(cadastral, area, 200, office));
        paid(applicant, claimed);
        paid(applicant, decided);
        accepted(intake, claimed);
        accepted(intake, decided);
        assertThat(act(reviewer, claimed, "review-claim", "{}").getStatus()).isEqualTo(200);
        assertThat(act(reviewer, decided, "review-claim", "{}").getStatus()).isEqualTo(200);
        assertThat(act(reviewer, decided, "recommendation", body("expectedVersion", version(reviewer, decided),
                "recommendation", "RECOMMEND_APPROVAL", "finding", "Title and boundaries are consistent")).getStatus()).isEqualTo(200);
        assertThat(act(authority, decided, "decision-claim", "{}").getStatus()).isEqualTo(200);
        String cited = json.readTree(authority.browser().send(get("/api/v1/assigned-cases/" + decided + "/documents"))
                .getContentAsString()).path("categories").get(0).path("versions").get(0).path("id").asString("");
        assertThat(act(authority, decided, "decision", body("expectedVersion", version(authority, decided), "outcome", "APPROVED",
                "grounds", "Title proven and boundaries agreed", "citedVersions", List.of(cited))).getStatus()).isEqualTo(200);

        SignedIn supervisor = signedIn("SUPERVISOR", office);
        JsonNode report = read(supervisor, get(WORKLOAD).param("office", office));
        assertThat(count(report.path("counts"), "COMPLETENESS_REVIEW", "cases")).isEqualTo(1);
        assertThat(count(report.path("counts"), "UNDER_REVIEW", "cases")).isEqualTo(1);
        assertThat(count(report.path("counts"), "APPROVED", "cases")).isEqualTo(1);
        assertThat(count(report.path("open"), "UNDER_REVIEW", "open")).isEqualTo(1);
        assertThat(count(report.path("open"), "UNDER_REVIEW", "overdue")).as("well within the DEMO target").isZero();
        assertThat(count(report.path("targets"), "COMPLETENESS_REVIEW", "seconds")).as("Baseline: 5 days").isEqualTo(432_000);
        JsonNode load = report.path("assignees").get(0);
        assertThat(load.path("state").asString("")).isEqualTo("UNDER_REVIEW");
        assertThat(load.path("cases").asLong()).isEqualTo(1);

        // Stage durations: one stay per departure recorded in the transition table, nothing else.
        assertThat(count(report.path("durations"), "COMPLETENESS_REVIEW", "stays")).isEqualTo(2);
        assertThat(count(report.path("durations"), "UNDER_REVIEW", "stays")).isEqualTo(1);
        assertThat(count(report.path("durations"), "PENDING_DECISION", "stays")).isEqualTo(1);
        assertThat(departures(office, "COMPLETENESS_REVIEW")).isEqualTo(2);

        // The queue: cases under way, longest waiting first, with who holds them.
        JsonNode cases = read(supervisor, get(WORKLOAD + "/cases").param("office", office));
        assertThat(cases.findValuesAsString("id")).containsExactly(waiting, claimed);
        assertThat(cases.get(0).path("assigneeName").isNull()).as("nobody holds a case in completeness review").isTrue();
        assertThat(cases.get(0).path("targetSeconds").asLong()).isEqualTo(432_000);
        assertThat(cases.get(0).path("overdue").asBoolean()).isFalse();
        assertThat(cases.get(1).path("assigneeName").asString("")).isNotBlank();
        assertThat(cases.get(1).path("waitedSeconds").asLong()).isNotNegative();
        assertThat(read(supervisor, get(WORKLOAD + "/cases").param("office", office).param("state", "UNDER_REVIEW"))
                .findValuesAsString("id")).containsExactly(claimed);
        assertThat(read(supervisor, get(WORKLOAD + "/cases").param("office", office).param("overdue", "true")).isEmpty()).isTrue();
        MockHttpServletResponse ended = supervisor.browser().send(get(WORKLOAD + "/cases").param("office", office).param("state", "APPROVED"));
        assertThat(ended.getStatus()).isEqualTo(422);
        assertThat(code(ended)).isEqualTo("STATE_NOT_LISTED");

        // The report holds no applicant name, form, document or review.
        assertThat(report.toString() + cases).doesNotContain("Demo Applicant", "Built in 2015", "Title and boundaries", "RECOMMEND_");
    }

    @Test
    void onlyHoldersOfTheReportPermissionAtTheOfficeReadIt() throws Exception {
        String office = "DEMOWLB";
        SignedIn elsewhere = signedIn("SUPERVISOR", "DEMOWLC");
        assertThat(elsewhere.browser().send(get(WORKLOAD).param("office", office)).getStatus()).as("another office").isEqualTo(403);
        assertThat(elsewhere.browser().send(get(WORKLOAD + "/cases").param("office", office)).getStatus()).isEqualTo(403);
        SignedIn intake = signedIn("INTAKE_OFFICER", office);
        assertThat(intake.browser().send(get(WORKLOAD).param("office", office)).getStatus()).as("no report.workload").isEqualTo(403);
        assertThat(applicant().send(get(WORKLOAD).param("office", office)).getStatus()).isEqualTo(403);
        SignedIn supervisor = signedIn("SUPERVISOR", office);
        JsonNode empty = read(supervisor, get(WORKLOAD).param("office", office));
        assertThat(empty.path("counts").isEmpty()).as("nothing submitted at this office yet").isTrue();
        assertThat(empty.path("targets")).hasSize(4);
    }

    private JsonNode read(SignedIn who, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = who.browser().send(request);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    /** Departures from a state recorded in the transition table of the office (as superuser: no row-level security). */
    private static long departures(String office, String state) throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.PreparedStatement query = su.prepareStatement("""
                     SELECT count(*) FROM snigf.application_transition t JOIN snigf.application a ON a.id = t.application_id
                       JOIN snigf.office o ON o.id = a.office_id WHERE o.code = ? AND t.from_state = ?""")) {
            query.setString(1, office);
            query.setString(2, state);
            try (java.sql.ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The value of {@code field} in the row of {@code state}; 0 when there is no such row. */
    private static long count(JsonNode rows, String state, String field) {
        for (JsonNode row : rows) {
            if (row.path("state").asString("").equals(state)) {
                return row.path(field).asLong();
            }
        }
        return 0;
    }
}
