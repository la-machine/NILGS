// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.document.DocumentFixtures;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * MVP-03 / E-14 through the real endpoints (Baseline acceptance: "missing category shows as
 * missing; request-info round trip preserves both document versions; accept with missing items
 * returns 409"; E-14 tests: "round trip keeps history; invalid transitions 409").
 */
class CompletenessIT extends CaseFlowIntegrationTest {

    static final String APPS = "/api/v1/applications";
    static final String CASES = "/api/v1/cases";

    @Test
    void theRoundTripKeepsEveryVersionAndTheWholeHistory() throws Exception {
        Browser applicant = applicant();
        String id = draft(applicant);
        assertThat(upload(applicant, id, "DEMO_IDENTITY", "identity-blurred.pdf", DocumentFixtures.pdf()).getStatus()).isEqualTo(201);
        JsonNode submitted = json.readTree(submit(applicant, id, 1).getContentAsString());
        assertThat(submitted.path("state").asString("")).isEqualTo("COMPLETENESS_REVIEW");

        // The intake officer sees the case in the office's queue, with what is missing.
        SignedIn intake = signedIn("INTAKE_OFFICER");
        JsonNode queue = json.readTree(intake.browser().send(get(CASES).param("office", "DEMOIT")
                .param("state", "COMPLETENESS_REVIEW")).getContentAsString());
        assertThat(queue.findValuesAsString("id")).contains(id);
        JsonNode open = caseOf(intake, id);
        assertThat(open.path("missing").toString()).contains("MISSING_DOCUMENT:DEMO_PROOF_OF_RIGHT")
                .doesNotContain("DEMO_IDENTITY");
        assertThat(open.path("applicantName").asString("")).isEqualTo("Demo Applicant");

        MockHttpServletResponse early = sendJson(intake.browser(), post(CASES + "/" + id + "/acceptance"),
                "{\"expectedVersion\":" + open.path("version").asLong() + "}");
        assertThat(early.getStatus()).isEqualTo(409);
        assertThat(code(early)).isEqualTo("ITEMS_MISSING");
        assertThat(json.readTree(early.getContentAsString()).path("items").toString()).contains("MISSING_DOCUMENT:DEMO_PROOF_OF_RIGHT");

        MockHttpServletResponse request = sendJson(intake.browser(), post(CASES + "/" + id + "/information-request"),
                json.writeValueAsString(Map.of("expectedVersion", open.path("version").asLong(),
                        "categories", List.of("DEMO_IDENTITY", "DEMO_PROOF_OF_RIGHT"),
                        "message", "The identity document is unreadable and the proof of right is missing")));
        assertThat(request.getStatus()).as(request.getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(request.getContentAsString()).path("state").asString("")).isEqualTo("INFO_REQUESTED");

        // The applicant sees the request and answers with files in the categories asked for only.
        JsonNode waiting = read(applicant, id);
        JsonNode asked = waiting.path("completeness").get(waiting.path("completeness").size() - 1);
        assertThat(asked.path("kind").asString("")).isEqualTo("INFO_REQUESTED");
        assertThat(asked.path("requested").toString()).contains("DEMO_IDENTITY", "DEMO_PROOF_OF_RIGHT");
        assertThat(code(upload(applicant, id, "DEMO_SITE_PLAN", "plan.png", DocumentFixtures.png(20, 20))))
                .isEqualTo("CATEGORY_NOT_REQUESTED");
        assertThat(upload(applicant, id, "DEMO_IDENTITY", "identity.pdf", DocumentFixtures.pdf("/Lang (fr)")).getStatus()).isEqualTo(201);
        assertThat(upload(applicant, id, "DEMO_PROOF_OF_RIGHT", "deed.pdf", DocumentFixtures.pdf("/Lang (en)")).getStatus()).isEqualTo(201);
        MockHttpServletResponse response = sendJson(applicant, post(APPS + "/" + id + "/response"),
                json.writeValueAsString(Map.of("expectedVersion", waiting.path("version").asLong(), "message", "Both files attached")));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(response.getContentAsString()).path("state").asString("")).isEqualTo("COMPLETENESS_REVIEW");

        // Both identity versions are kept; nothing is missing; the officer accepts.
        JsonNode documents = json.readTree(intake.browser().send(get(CASES + "/" + id + "/documents")).getContentAsString());
        assertThat(documents.path("categories").get(0).path("versions").size()).isEqualTo(2);
        JsonNode back = caseOf(intake, id);
        assertThat(back.path("missing").size()).isZero();
        // Nothing is missing, but the DEMO fee is unpaid: the gate holds the acceptance (E-17).
        assertThat(back.path("payment").path("standing").asString("")).isEqualTo("DUE");
        assertThat(back.path("payment").path("blocksAcceptance").asBoolean()).isTrue();
        MockHttpServletResponse unpaid = sendJson(intake.browser(), post(CASES + "/" + id + "/acceptance"),
                "{\"expectedVersion\":" + back.path("version").asLong() + "}");
        assertThat(unpaid.getStatus()).isEqualTo(409);
        assertThat(code(unpaid)).isEqualTo("PAYMENT_REQUIRED");
        paid(applicant, id);
        assertThat(caseOf(intake, id).path("payment").path("standing").asString("")).isEqualTo("PAID");
        MockHttpServletResponse accepted = sendJson(intake.browser(), post(CASES + "/" + id + "/acceptance"),
                "{\"expectedVersion\":" + back.path("version").asLong() + "}");
        assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(200);
        JsonNode done = json.readTree(accepted.getContentAsString());
        assertThat(done.path("state").asString("")).isEqualTo("UNDER_REVIEW");
        assertThat(done.path("history").findValuesAsString("to")).containsExactly(
                "SUBMITTED", "COMPLETENESS_REVIEW", "INFO_REQUESTED", "COMPLETENESS_REVIEW", "UNDER_REVIEW");
        assertThat(done.path("completeness").findValuesAsString("kind")).containsExactly(
                "AUTOMATIC", "INFO_REQUESTED", "RESPONSE", "AUTOMATIC", "COMPLETE");

        // Invalid transitions: 409, and recorded.
        MockHttpServletResponse twice = sendJson(intake.browser(), post(CASES + "/" + id + "/acceptance"),
                "{\"expectedVersion\":" + done.path("version").asLong() + "}");
        assertThat(twice.getStatus()).isEqualTo(409);
        assertThat(code(twice)).isEqualTo("INVALID_TRANSITION");
        MockHttpServletResponse late = sendJson(applicant, post(APPS + "/" + id + "/response"),
                "{\"expectedVersion\":" + done.path("version").asLong() + "}");
        assertThat(late.getStatus()).isEqualTo(409);
        for (String action : List.of("COMPLETENESS_INFO_REQUESTED", "COMPLETENESS_RESPONSE_SENT", "COMPLETENESS_ACCEPTED",
                "COMPLETENESS_REJECTED")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = ?",
                    Long.class, id, action)).as(action).isPositive();
        }
    }

    @Test
    void onlyIntakeOfficersOfTheOfficeDecideAndARequestNamesCategoriesAndExplainsItself() throws Exception {
        Browser applicant = applicant();
        String id = draft(applicant);
        submit(applicant, id, 1);
        SignedIn intake = signedIn("INTAKE_OFFICER");
        long version = caseOf(intake, id).path("version").asLong();

        SignedIn elsewhere = signedIn("INTAKE_OFFICER", "DEMOIT2");
        assertThat(elsewhere.browser().send(get(CASES + "/" + id)).getStatus()).isEqualTo(403);
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        assertThat(cadastral.browser().send(get(CASES + "/" + id)).getStatus()).as("reads applications of the office").isEqualTo(200);
        assertThat(sendJson(cadastral.browser(), post(CASES + "/" + id + "/acceptance"), "{\"expectedVersion\":" + version + "}")
                .getStatus()).as("but does not decide completeness").isEqualTo(403);
        assertThat(applicant.send(get(CASES + "/" + id)).getStatus()).as("the applicant reads it as theirs, not as a case")
                .isEqualTo(403);

        assertThat(code(request(intake, id, version, List.of(), "Please complete the application"))).isEqualTo("CATEGORIES_REQUIRED");
        assertThat(code(request(intake, id, version, List.of("DEMO_SALARY"), "Please send your salary slip")))
                .isEqualTo("CATEGORY_UNKNOWN");
        assertThat(code(request(intake, id, version, List.of("DEMO_IDENTITY"), "Send it"))).isEqualTo("MESSAGE_REQUIRED");
        assertThat(code(request(intake, id, version + 7, List.of("DEMO_IDENTITY"), "Please send your identity document")))
                .isEqualTo("STALE_VERSION");
    }

    private MockHttpServletResponse request(SignedIn officer, String id, long version, List<String> categories, String message)
            throws Exception {
        return sendJson(officer.browser(), post(CASES + "/" + id + "/information-request"), json.writeValueAsString(
                Map.of("expectedVersion", version, "categories", categories, "message", message)));
    }

    private JsonNode caseOf(SignedIn staff, String id) throws Exception {
        MockHttpServletResponse response = staff.browser().send(get(CASES + "/" + id));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    /** A complete form with an active parcel: only documents may be missing. */
    private String draft(Browser applicant) throws Exception {
        MockHttpServletResponse created = sendJson(applicant, post(APPS).header("Idempotency-Key", key()),
                "{\"typeCode\":\"DEMO_REGISTRATION\",\"data\":{}}");
        String id = json.readTree(created.getContentAsString()).path("id").asString("");
        MockHttpServletResponse saved = sendJson(applicant, put(APPS + "/" + id), json.writeValueAsString(Map.of(
                "expectedVersion", 0, "parcelReference", parcel(),
                "data", Map.of("applicantCapacity", "OWNER", "landUse", "RESIDENTIAL", "statement", "Built in 2015 (synthetic)"))));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
        return id;
    }

    private MockHttpServletResponse submit(Browser applicant, String id, long version) throws Exception {
        MockHttpServletResponse response = sendJson(applicant, post(APPS + "/" + id + "/submission").header("Idempotency-Key", key()),
                "{\"expectedVersion\":" + version + "}");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    private MockHttpServletResponse upload(Browser who, String id, String category, String name, byte[] bytes) throws Exception {
        who.csrf();
        return who.send(multipart(APPS + "/" + id + "/documents/" + category)
                .file(new MockMultipartFile("file", name, "application/pdf", bytes)).header("Idempotency-Key", key()));
    }

    private JsonNode read(Browser who, String id) throws Exception {
        return json.readTree(who.send(get(APPS + "/" + id)).getContentAsString());
    }

    private String parcel() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double e = 300_000 + r.nextLong(0, 200) * 1000;
        double n = 500_000 + r.nextLong(0, 300) * 1000;
        String square = "{\"type\":\"Polygon\",\"coordinates\":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}".formatted(
                e, n, e + 20, n, e + 20, n + 20, e, n + 20, e, n);
        return json.readTree(sendJson(officer.browser(), post("/api/v1/parcels").header("Idempotency-Key", key()),
                "{\"officeCode\":\"DEMOIT\",\"locality\":\"Completeness fixture\",\"srid\":32633,\"geometry\":" + square + "}")
                .getContentAsString()).path("reference").asString("");
    }
}
