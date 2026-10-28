// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.document.DocumentFixtures;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MVP-06 / E-16, the applicant's side, through the real endpoints: at every step of the life of an
 * application the applicant reads its state, the date it entered it, who acts next and what is
 * asked of them, and never anything internal (Baseline acceptance: "the applicant API response
 * contains no internal fields (tested by schema)", {@link ApplicantPayloadSchema}).
 */
class TrackingIT extends CaseFlowIntegrationTest {

    static final String REQUEST = "The identity document is unreadable, please scan it again";
    static final String FINDING = "The proof of right does not name the applicant";
    static final String RETURN = "Check the proof of right against the parcel register";
    static final String SECOND_FINDING = "Confirmed with the register: the proof of right is in order";

    @Test
    void theApplicantFollowsEveryStepAndNeverReadsAnythingInternal() throws Exception {
        Browser applicant = applicant();
        String parcel = parcel(signedIn("CADASTRAL_OFFICER"), area(), 0);
        String id = submitted(applicant, parcel);
        JsonNode mine = mine(applicant, id);
        assertThat(mine.path("state").asString("")).isEqualTo("COMPLETENESS_REVIEW");
        assertThat(mine.path("waitingOn").asString("")).isEqualTo("OFFICE");
        assertThat(mine.path("history").findValuesAsString("by")).containsExactly("APPLICANT", "SYSTEM");
        assertThat(mine.path("since").asString("")).as("the date entered: the last move of the history")
                .isEqualTo(last(mine.path("history")).path("at").asString(""));

        // The office asks for information: the applicant reads the request, and acts next.
        SignedIn intake = signedIn("INTAKE_OFFICER");
        long version = json.readTree(intake.browser().send(get("/api/v1/cases/" + id)).getContentAsString()).path("version").asLong();
        assertThat(sendJson(intake.browser(), post("/api/v1/cases/" + id + "/information-request"), json.writeValueAsString(
                Map.of("expectedVersion", version, "categories", List.of("DEMO_IDENTITY"), "message", REQUEST))).getStatus()).isEqualTo(200);
        mine = mine(applicant, id);
        assertThat(mine.path("waitingOn").asString("")).isEqualTo("APPLICANT");
        assertThat(mine.path("pendingRequest").path("categories").get(0).asString("")).isEqualTo("DEMO_IDENTITY");
        assertThat(mine.path("pendingRequest").path("message").asString("")).as("written to the applicant").isEqualTo(REQUEST);
        assertThat(last(mine.path("history")).path("by").asString("")).isEqualTo("OFFICE");
        ApplicantPayloadSchema.assertSafe(mine);

        // The applicant answers with a new version of the document.
        applicant.csrf();
        assertThat(applicant.send(multipart(APPS + "/" + id + "/documents/DEMO_IDENTITY")
                .file(new MockMultipartFile("file", "identity.pdf", "application/pdf", DocumentFixtures.pdf("/Lang (rescanned)")))
                .header("Idempotency-Key", key())).getStatus()).isEqualTo(201);
        assertThat(sendJson(applicant, post(APPS + "/" + id + "/response"), json.writeValueAsString(
                Map.of("expectedVersion", mine.path("version").asLong(), "message", "Scanned again"))).getStatus()).isEqualTo(200);
        mine = mine(applicant, id);
        assertThat(mine.path("pendingRequest").isNull()).as("answered: nothing pending").isTrue();
        assertThat(mine.path("waitingOn").asString("")).isEqualTo("OFFICE");
        ApplicantPayloadSchema.assertSafe(mine);

        // Review: a refusal recommended, returned, recommended again, decided.
        paid(applicant, id);
        accepted(intake, id);
        SignedIn reviewer = signedIn("REVIEWER");
        SignedIn authority = signedIn("DECISION_AUTHORITY");
        assertThat(act(reviewer, id, "review-claim", "{}").getStatus()).isEqualTo(200);
        assertThat(act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id),
                "recommendation", "RECOMMEND_REFUSAL", "finding", FINDING)).getStatus()).isEqualTo(200);
        assertSafeNow(applicant, id);
        assertThat(act(authority, id, "decision-claim", "{}").getStatus()).isEqualTo(200);
        assertThat(act(authority, id, "return", body("expectedVersion", version(authority, id), "reason", RETURN)).getStatus())
                .isEqualTo(200);
        assertSafeNow(applicant, id);
        assertThat(act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id),
                "recommendation", "RECOMMEND_APPROVAL", "finding", SECOND_FINDING)).getStatus()).isEqualTo(200);
        String cited = json.readTree(authority.browser().send(get("/api/v1/assigned-cases/" + id + "/documents"))
                .getContentAsString()).path("categories").get(0).path("versions").get(0).path("id").asString("");
        MockHttpServletResponse decided = act(authority, id, "decision", body("expectedVersion", version(authority, id),
                "outcome", "APPROVED", "grounds", "Title proven and boundaries agreed", "citedVersions", List.of(cited)));
        assertThat(decided.getStatus()).as(decided.getContentAsString()).isEqualTo(200);

        mine = mine(applicant, id);
        assertThat(mine.path("state").asString("")).isEqualTo("APPROVED");
        assertThat(mine.path("waitingOn").isNull()).as("ended: nobody acts next").isTrue();
        assertThat(mine.path("history").findValuesAsString("to")).endsWith("UNDER_REVIEW", "PENDING_DECISION", "APPROVED");
        assertThat(mine.path("decision").path("simulated").asBoolean()).isTrue();
        assertSafeNow(applicant, id);
    }

    @Test
    void aDraftIsTheApplicantsToFinishAndDatesFromItsCreation() throws Exception {
        Browser applicant = applicant();
        String id = json.readTree(sendJson(applicant, post(APPS).header("Idempotency-Key", key()),
                "{\"typeCode\":\"DEMO_REGISTRATION\",\"data\":{}}").getContentAsString()).path("id").asString("");
        JsonNode mine = mine(applicant, id);
        assertThat(mine.path("state").asString("")).isEqualTo("DRAFT");
        assertThat(mine.path("waitingOn").asString("")).isEqualTo("APPLICANT");
        assertThat(mine.path("since").asString("")).isEqualTo(mine.path("createdAt").asString(""));
        assertThat(mine.path("history").isEmpty()).isTrue();
        ApplicantPayloadSchema.assertSafe(mine);
    }

    private void assertSafeNow(Browser applicant, String id) throws Exception {
        ApplicantPayloadSchema.assertSafe(mine(applicant, id), FINDING, RETURN, SECOND_FINDING, "RECOMMEND_", "RETURNED");
    }

    private JsonNode mine(Browser applicant, String id) throws Exception {
        MockHttpServletResponse response = applicant.send(get(APPS + "/" + id));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private static JsonNode last(JsonNode list) {
        return list.get(list.size() - 1);
    }
}
