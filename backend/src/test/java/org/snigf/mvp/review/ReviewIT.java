// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.ApplicantPayloadSchema;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MVP-05 / E-15 through the real endpoints (Baseline acceptance: "decision without grounds → 422;
 * reviewer recording a decision → 403; decision on a case not pending decision → 409"; E-15 tests:
 * "same person blocked; open alert blocks"), and the simulated label on what the applicant reads.
 */
class ReviewIT extends CaseFlowIntegrationTest {

    @Test
    void reviewedThenDecidedByAnotherPersonOnlyOnceNoAlertIsOpenWithGroundsAndCitations() throws Exception {
        Browser applicant = applicant();
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        long[] area = area();
        String parcel = parcel(cadastral, area, 0);
        String id = underReview(applicant, parcel);

        // Review: claimed by one reviewer, invisible to the others, recommended.
        SignedIn reviewer = signedIn("REVIEWER");
        SignedIn otherReviewer = signedIn("REVIEWER");
        assertThat(json.readTree(reviewer.browser().send(get(REVIEWS).param("stage", "REVIEW")).getContentAsString())
                .findValuesAsString("id")).contains(id);
        assertThat(act(reviewer, id, "review-claim", "{}").getStatus()).isEqualTo(200);
        assertThat(otherReviewer.browser().send(get(REVIEWS + "/" + id)).getStatus()).as("claimed: invisible to others").isEqualTo(404);
        assertThat(json.readTree(reviewer.browser().send(get(REVIEWS).param("stage", "REVIEW").param("mine", "true"))
                .getContentAsString()).findValuesAsString("id")).contains(id);
        assertThat(reviewer.browser().send(get("/api/v1/assigned-cases/" + id)).getStatus()).isEqualTo(200);
        assertThat(code(act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id), "recommendation", "RECOMMEND_APPROVAL",
                "finding", "ok")))).isEqualTo("FINDING_REQUIRED");
        assertThat(act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id), "recommendation", "RECOMMEND_APPROVAL",
                "finding", "Title and boundaries are consistent")).getStatus()).isEqualTo(200);

        // The reviewer never decides: no decision.record (403).
        assertThat(act(reviewer, id, "decision", body("expectedVersion", version(reviewer, id), "outcome", "APPROVED",
                "grounds", "Reviewer trying to decide", "citedVersions", List.of())).getStatus()).isEqualTo(403);

        // Decision: a decision authority claims it; grounds, citations, and the open alert gate.
        SignedIn authority = signedIn("DECISION_AUTHORITY");
        assertThat(act(authority, id, "decision-claim", "{}").getStatus()).isEqualTo(200);
        String cited = json.readTree(authority.browser().send(get("/api/v1/assigned-cases/" + id + "/documents"))
                .getContentAsString()).path("categories").get(0).path("versions").get(0).path("id").asString("");
        MockHttpServletResponse noGrounds = act(authority, id, "decision", body("expectedVersion", version(authority, id), "outcome", "APPROVED",
                "grounds", "", "citedVersions", List.of(cited)));
        assertThat(noGrounds.getStatus()).isEqualTo(422);
        assertThat(code(noGrounds)).isEqualTo("GROUNDS_REQUIRED");
        assertThat(code(act(authority, id, "decision", body("expectedVersion", version(authority, id), "outcome", "APPROVED",
                "grounds", "Title proven and boundaries agreed", "citedVersions", List.of())))).isEqualTo("CITATIONS_REQUIRED");

        parcel(cadastral, area, 10);   // overlapping the application's parcel: an open alert
        MockHttpServletResponse blocked = act(authority, id, "decision", body("expectedVersion", version(authority, id), "outcome", "APPROVED",
                "grounds", "Title proven and boundaries agreed", "citedVersions", List.of(cited)));
        assertThat(blocked.getStatus()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("OPEN_ALERT");
        JsonNode review = json.readTree(authority.browser().send(get(REVIEWS + "/" + id)).getContentAsString());
        assertThat(review.path("openAlerts").asInt()).isPositive();

        // Returned to review; recommended again; the alert resolved by a colleague; decided.
        assertThat(act(authority, id, "return", body("expectedVersion", version(authority, id), "reason",
                "Please look at the overlap with the neighbour first")).getStatus()).isEqualTo(200);
        MockHttpServletResponse notPending = act(authority, id, "decision", body("expectedVersion", version(authority, id), "outcome", "APPROVED",
                "grounds", "Title proven and boundaries agreed", "citedVersions", List.of(cited)));
        assertThat(notPending.getStatus()).as("a decision on a case not pending decision").isEqualTo(409);
        assertThat(code(notPending)).isEqualTo("INVALID_TRANSITION");
        assertThat(act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id), "recommendation", "RECOMMEND_APPROVAL",
                "finding", "The overlap is a survey error of the neighbour")).getStatus()).isEqualTo(200);
        resolveAlerts(parcel);
        MockHttpServletResponse decided = act(authority, id, "decision", body("expectedVersion", version(authority, id), "outcome", "APPROVED",
                "grounds", "Title proven and boundaries agreed", "citedVersions", List.of(cited)));
        assertThat(decided.getStatus()).as(decided.getContentAsString()).isEqualTo(200);
        JsonNode done = json.readTree(decided.getContentAsString());
        assertThat(done.path("state").asString("")).isEqualTo("APPROVED");
        assertThat(done.path("decision").path("simulated").asBoolean()).isTrue();
        assertThat(code(act(authority, id, "decision", body("expectedVersion", version(authority, id), "outcome", "REFUSED",
                "grounds", "Changing my mind afterwards", "citedVersions", List.of(cited))))).isEqualTo("INVALID_TRANSITION");

        // The applicant reads the decision, simulated, and nothing of the internal review.
        JsonNode mine = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        assertThat(mine.path("state").asString("")).isEqualTo("APPROVED");
        assertThat(mine.path("decision").path("outcome").asString("")).isEqualTo("APPROVED");
        assertThat(mine.path("decision").path("simulated").asBoolean()).isTrue();
        assertThat(mine.toString()).doesNotContain("survey error of the neighbour");
        ApplicantPayloadSchema.assertSafe(mine, "Title and boundaries are consistent", "Please look at the overlap with the neighbour first",
                "RECOMMEND_APPROVAL", "RETURNED");
        for (String action : List.of("CASE_CLAIMED", "REVIEW_RECOMMENDED", "DECISION_RETURNED", "DECISION_RECORDED", "REVIEW_REJECTED")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = ?",
                    Long.class, id, action)).as(action).isPositive();
        }
    }

    @Test
    void whoeverRecommendedNeverDecidesEvenHoldingBothRoles() throws Exception {
        Browser applicant = applicant();
        String id = underReview(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        SignedIn both = signedIn("REVIEWER");
        // Permissions are rebuilt from the database on every request: the second role applies at once.
        addRole(both.id(), "DECISION_AUTHORITY");
        assertThat(act(both, id, "review-claim", "{}").getStatus()).isEqualTo(200);
        assertThat(act(both, id, "recommendation", body("expectedVersion", version(both, id), "recommendation", "RECOMMEND_REFUSAL",
                "finding", "The title does not match the parcel")).getStatus()).isEqualTo(200);
        MockHttpServletResponse self = act(both, id, "decision-claim", "{}");
        assertThat(self.getStatus()).isEqualTo(403);
        assertThat(code(self)).isEqualTo("RECOMMENDER_CANNOT_DECIDE");
        SignedIn elsewhere = signedIn("DECISION_AUTHORITY", "DEMOIT2");
        assertThat(act(elsewhere, id, "decision-claim", "{}").getStatus()).as("another office: not even visible").isEqualTo(404);
        SignedIn intake = signedIn("INTAKE_OFFICER");
        assertThat(intake.browser().send(get("/api/v1/cases/" + id + "/review")).getStatus()).as("office readers see the review")
                .isEqualTo(200);
        assertThat(applicant.send(get(REVIEWS + "/" + id)).getStatus()).as("the applicant: not their endpoint").isEqualTo(403);
    }
}
