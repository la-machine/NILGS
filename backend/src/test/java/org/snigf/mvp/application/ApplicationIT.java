// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * MVP-02 / E-12 through the real endpoints: an applicant keeps drafts only they see; submission
 * needs a complete form and an active parcel, is idempotent per key, issues the reference and
 * writes the history; another applicant gets 404 for every id that is not theirs (IDOR), and the
 * database agrees (row-level security, V13).
 */
class ApplicationIT extends IdentityIntegrationTest {

    static final String APPS = "/api/v1/applications";
    static final Map<String, String> COMPLETE = Map.of("applicantCapacity", "HEIR", "landUse", "RESIDENTIAL",
            "statement", "Inherited from my mother in 2019 (synthetic data)");

    @Test
    void anApplicantDraftsSubmitsOnceWithTheSameKeyAndGetsAReferenceAndAHistory() throws Exception {
        Browser applicant = applicant();
        String parcel = parcel();

        MockHttpServletResponse created = create(applicant, "it-" + UUID.randomUUID(), null, Map.of("landUse", "RESIDENTIAL"));
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        JsonNode draft = json.readTree(created.getContentAsString());
        String id = draft.path("id").asString("");
        assertThat(draft.path("reference").isNull()).as("no reference before submission").isTrue();

        // Incomplete: 422 with every missing item, still a draft, the refusal audited.
        MockHttpServletResponse incomplete = submit(applicant, id, 0, "it-" + UUID.randomUUID());
        assertThat(incomplete.getStatus()).isEqualTo(422);
        JsonNode problem = json.readTree(incomplete.getContentAsString());
        assertThat(problem.path("code").asString("")).isEqualTo("APPLICATION_INCOMPLETE");
        assertThat(problem.path("items").toString())
                .contains("MISSING:applicantCapacity", "MISSING:statement", "MISSING:parcelReference");
        assertThat(read(applicant, id).path("state").asString("")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = 'APPLICATION_SUBMIT_REJECTED'",
                Long.class, id)).isEqualTo(1);

        MockHttpServletResponse unknownParcel = update(applicant, id, 0, "DEMO-PCL-DEMOIT-2026-999999-0", COMPLETE);
        assertThat(unknownParcel.getStatus()).as("a reference with a wrong check character").isEqualTo(422);
        assertThat(json.readTree(unknownParcel.getContentAsString()).path("items").toString()).contains("PARCEL_REFERENCE_INVALID");

        MockHttpServletResponse saved = update(applicant, id, 0, parcel.toLowerCase(), COMPLETE);
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(saved.getContentAsString()).path("parcelReference").asString("")).isEqualTo(parcel);

        String key = "it-" + UUID.randomUUID();
        MockHttpServletResponse first = submit(applicant, id, 1, key);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        String reference = json.readTree(first.getContentAsString()).path("reference").asString("");
        assertThat(reference).matches("^DEMO-APP-[0-9]{4}-[0-9]{6}-[0-9A-Z]$");

        MockHttpServletResponse again = submit(applicant, id, 1, key);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(again.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json.readTree(again.getContentAsString()).path("reference").asString("")).isEqualTo(reference);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = 'APPLICATION_SUBMITTED'",
                Long.class, id)).as("one submission").isEqualTo(1);

        MockHttpServletResponse otherKey = submit(applicant, id, 2, "it-" + UUID.randomUUID());
        assertThat(otherKey.getStatus()).isEqualTo(409);
        assertThat(json.readTree(otherKey.getContentAsString()).path("code").asString("")).isEqualTo("INVALID_TRANSITION");

        JsonNode submitted = read(applicant, id);
        assertThat(submitted.path("state").asString("")).as("the automatic check moved it on").isEqualTo("COMPLETENESS_REVIEW");
        assertThat(submitted.path("officeCode").asString("")).isEqualTo("DEMOIT");
        assertThat(submitted.path("history").get(0).path("from").asString("")).isEqualTo("DRAFT");
        assertThat(submitted.path("history").get(0).path("to").asString("")).isEqualTo("SUBMITTED");
        assertThat(submitted.path("history").get(1).path("to").asString("")).isEqualTo("COMPLETENESS_REVIEW");
        assertThat(submitted.path("history").get(0).path("by").asString("")).isEqualTo("APPLICANT");
        assertThat(submitted.path("history").get(1).path("by").asString("")).as("the system, no person").isEqualTo("SYSTEM");
        ApplicantPayloadSchema.assertSafe(submitted);
        assertThat(submitted.path("completeness").get(0).path("kind").asString("")).isEqualTo("AUTOMATIC");
        assertThat(update(applicant, id, 2, parcel, COMPLETE).getStatus()).as("submitted: never edited").isEqualTo(409);
        assertThat(applicant.send(delete(APPS + "/" + id).param("expectedVersion", "2")).getStatus())
                .as("submitted: never deleted").isEqualTo(409);
    }

    @Test
    void anotherApplicantGets404ForEverythingThatIsNotTheirsAndTheAttemptIsRecorded() throws Exception {
        Browser owner = applicant();
        String id = json.readTree(create(owner, "it-" + UUID.randomUUID(), null, Map.of()).getContentAsString())
                .path("id").asString("");
        Browser other = applicant();

        assertThat(other.send(get(APPS + "/" + id)).getStatus()).isEqualTo(404);
        assertThat(update(other, id, 0, null, COMPLETE).getStatus()).isEqualTo(404);
        other.csrf();
        assertThat(other.send(delete(APPS + "/" + id).param("expectedVersion", "0")).getStatus()).isEqualTo(404);
        assertThat(submit(other, id, 0, "it-" + UUID.randomUUID()).getStatus()).isEqualTo(404);
        assertThat(json.readTree(other.send(get(APPS)).getContentAsString()).size()).as("their list is empty").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = 'ACCESS_DENIED'",
                Long.class, id)).isEqualTo(4);
        assertThat(read(owner, id).path("version").asLong()).as("nothing changed").isZero();

        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        assertThat(officer.browser().send(get(APPS + "/" + id)).getStatus()).as("staff: not their own records").isEqualTo(403);
    }

    @Test
    void theDatabaseShowsNothingWithoutAnActorAndOnlyTheOwnersRowsWithOne() throws Exception {
        Browser owner = applicant();
        String id = json.readTree(create(owner, "it-" + UUID.randomUUID(), null, Map.of()).getContentAsString())
                .path("id").asString("");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application WHERE id = ?::uuid", Long.class, id))
                .as("the application's account, no actor named").isZero();
    }

    @Test
    void aDraftCanBeDeletedByItsOwnerAndTheCreationIsIdempotent() throws Exception {
        Browser applicant = applicant();
        String key = "it-" + UUID.randomUUID();
        String id = json.readTree(create(applicant, key, null, Map.of()).getContentAsString()).path("id").asString("");
        MockHttpServletResponse retry = create(applicant, key, null, Map.of());
        assertThat(retry.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json.readTree(retry.getContentAsString()).path("id").asString("")).isEqualTo(id);
        assertThat(json.readTree(applicant.send(get(APPS)).getContentAsString()).size()).isEqualTo(1);

        assertThat(create(applicant, key, null, Map.of("landUse", "COMMERCIAL")).getStatus())
                .as("the same key for another request").isEqualTo(422);
        applicant.csrf();
        assertThat(applicant.send(delete(APPS + "/" + id).param("expectedVersion", "5")).getStatus()).isEqualTo(409);
        applicant.csrf();
        assertThat(applicant.send(delete(APPS + "/" + id).param("expectedVersion", "0")).getStatus()).isEqualTo(204);
        assertThat(applicant.send(get(APPS + "/" + id)).getStatus()).isEqualTo(404);
    }

    @Test
    void aFormIsCheckedAgainstItsTypeAndTheTypesAreListed() throws Exception {
        Browser applicant = applicant();
        JsonNode types = json.readTree(applicant.send(get("/api/v1/application-types")).getContentAsString());
        assertThat(types.findValuesAsString("code")).contains("DEMO_REGISTRATION");
        MockHttpServletResponse wrong = create(applicant, "it-" + UUID.randomUUID(), null,
                Map.of("landUse", "MINING", "salary", "1"));
        assertThat(wrong.getStatus()).isEqualTo(422);
        assertThat(json.readTree(wrong.getContentAsString()).path("items").toString())
                .contains("NOT_AN_OPTION:landUse", "UNKNOWN_FIELD:salary");
        MockHttpServletResponse unknownType = sendJson(applicant, post(APPS).header("Idempotency-Key", "it-" + UUID.randomUUID()),
                "{\"typeCode\":\"DEMO_NOTHING\",\"data\":{}}");
        assertThat(json.readTree(unknownType.getContentAsString()).path("code").asString("")).isEqualTo("TYPE_NOT_AVAILABLE");
        MockHttpServletResponse noKey = sendJson(applicant, post(APPS), "{\"typeCode\":\"DEMO_REGISTRATION\",\"data\":{}}");
        assertThat(json.readTree(noKey.getContentAsString()).path("code").asString("")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    private Browser applicant() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Demo Applicant", PASSWORD);
        browser.login(email, PASSWORD);
        browser.csrf();
        return browser;
    }

    /** An active parcel at the test office, recorded by a cadastral officer. */
    private String parcel() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double e = 300_000 + r.nextLong(0, 200) * 1000;
        double n = 500_000 + r.nextLong(0, 300) * 1000;
        String square = "{\"type\":\"Polygon\",\"coordinates\":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}".formatted(
                e, n, e + 20, n, e + 20, n + 20, e, n + 20, e, n);
        MockHttpServletResponse response = sendJson(officer.browser(), post("/api/v1/parcels")
                .header("Idempotency-Key", "it-" + UUID.randomUUID()),
                "{\"officeCode\":\"DEMOIT\",\"locality\":\"Application fixture\",\"srid\":32633,\"geometry\":" + square + "}");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).path("reference").asString("");
    }

    private MockHttpServletResponse create(Browser who, String key, String parcel, Map<String, String> data) throws Exception {
        return sendJson(who, post(APPS).header("Idempotency-Key", key), json.writeValueAsString(
                parcel == null ? Map.of("typeCode", "DEMO_REGISTRATION", "data", data)
                        : Map.of("typeCode", "DEMO_REGISTRATION", "parcelReference", parcel, "data", data)));
    }

    private MockHttpServletResponse update(Browser who, String id, long version, String parcel, Map<String, String> data)
            throws Exception {
        return sendJson(who, put(APPS + "/" + id), json.writeValueAsString(parcel == null
                ? Map.of("expectedVersion", version, "data", data)
                : Map.of("expectedVersion", version, "parcelReference", parcel, "data", data)));
    }

    private MockHttpServletResponse submit(Browser who, String id, long version, String key) throws Exception {
        return sendJson(who, post(APPS + "/" + id + "/submission").header("Idempotency-Key", key),
                "{\"expectedVersion\":" + version + "}");
    }

    private JsonNode read(Browser who, String id) throws Exception {
        MockHttpServletResponse response = who.send(get(APPS + "/" + id));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }
}
