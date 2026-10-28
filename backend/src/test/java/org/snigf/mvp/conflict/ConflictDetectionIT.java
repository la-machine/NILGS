// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.conflict.application.ConflictPolicyService;
import org.snigf.mvp.conflict.application.ConflictProperties;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MVP-04 / E-10 through the real endpoints: each version written is checked by the database;
 * the officer sees the alerts it raised in the answer; the alerts are listed for the parcel's
 * reviewers, audited, superseded by a correction, and governed by the configured thresholds.
 * Each test works in its own random area (UTM 33N metres), away from other tests' parcels.
 */
class ConflictDetectionIT extends IdentityIntegrationTest {

    static final String PARCELS = "/api/v1/parcels";

    @Autowired
    ConflictPolicyService policy;

    /** A rectangle in UTM 33N metres, relative to this test's own origin. */
    private record Area(long e, long n) {
        static Area random() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            return new Area(300_000 + r.nextLong(0, 200) * 1000, 500_000 + r.nextLong(0, 300) * 1000);
        }

        String rect(double x0, double y0, double x1, double y1) {
            return """
                    {"type":"Polygon","coordinates":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}""".formatted(
                    e + x0, n + y0, e + x1, n + y0, e + x1, n + y1, e + x0, n + y1, e + x0, n + y0);
        }
    }

    @Test
    void anOverlapIsInTheAnswerAuditedAndListedForTheParcelsReviewers() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        Area area = Area.random();
        String a = reference(create(officer, area.rect(0, 0, 30, 30), null));
        JsonNode b = json.readTree(create(officer, area.rect(10, 0, 40, 30), null).getContentAsString());

        JsonNode alert = b.path("alerts").get(0);
        assertThat(b.path("alerts").size()).isEqualTo(1);
        assertThat(alert.path("type").asString("")).isEqualTo("OVERLAP");
        assertThat(alert.path("otherReference").asString("")).isEqualTo(a);
        assertThat(alert.path("intersectionM2").asDouble()).as("20 m x 30 m on the spheroid").isBetween(590.0, 610.0);
        assertThat(alert.path("shareOfThisParcel").asDouble()).isBetween(0.65, 0.68);

        String bRef = b.path("reference").asString("");
        assertThat(jdbc.queryForObject("SELECT action || ':' || actor_id || ':' || (details->>'otherParcel')"
                + " FROM audit_event WHERE entity_ref = ? AND action = 'CONFLICT_ALERT_RAISED'", String.class, bRef))
                .isEqualTo("CONFLICT_ALERT_RAISED:" + officer.id() + ":" + a);

        JsonNode listed = json.readTree(officer.browser().send(get(PARCELS + "/" + a + "/alerts")).getContentAsString());
        assertThat(listed.size()).isEqualTo(1);
        assertThat(listed.get(0).path("status").asString("")).isEqualTo("OPEN");
        assertThat(listed.get(0).path("otherReference").asString("")).isEqualTo(bRef);
        assertThat(listed.get(0).path("thisVersionNo").asInt()).isEqualTo(1);
        assertThat(listed.get(0).path("intersection").path("type").asString("")).isEqualTo("MultiPolygon");

        SignedIn reviewer = signedIn("REVIEWER");
        assertThat(reviewer.browser().send(get(PARCELS + "/" + a + "/alerts")).getStatus())
                .as("reading parcels is not reviewing alerts").isEqualTo(403);
        SignedIn elsewhere = signedIn("CADASTRAL_OFFICER", "DEMOIT2");
        assertThat(elsewhere.browser().send(get(PARCELS + "/" + a + "/alerts")).getStatus())
                .as("alerts are reviewed at the parcel's office").isEqualTo(403);
    }

    @Test
    void touchingParcelsRaiseNothing() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        Area area = Area.random();
        create(officer, area.rect(0, 0, 30, 30), null);
        assertThat(json.readTree(create(officer, area.rect(30, 0, 60, 30), null).getContentAsString())
                .path("alerts").size()).as("shared edge").isZero();
        assertThat(json.readTree(create(officer, area.rect(30, 30, 60, 60), null).getContentAsString())
                .path("alerts").size()).as("shared corner").isZero();
    }

    @Test
    void aCopyIsADuplicateAndSoIsTheSameExternalReferenceWrittenDifferently() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        Area area = Area.random();
        String suffix = Long.toString(area.e() + area.n(), 36).toUpperCase();
        create(officer, area.rect(0, 0, 30, 30), "TF 77/Mfoundi-" + suffix);
        JsonNode copy = json.readTree(create(officer, area.rect(0, 0, 30, 30), "tf77 mfoundi " + suffix).getContentAsString());
        assertThat(copy.path("alerts").findValuesAsString("type"))
                .containsExactlyInAnyOrder("DUPLICATE_GEOMETRY", "DUPLICATE_REFERENCE");
    }

    @Test
    void aCorrectionSupersedesTheParcelsAlertsAndIsCheckedAfresh() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        Area area = Area.random();
        String a = reference(create(officer, area.rect(0, 0, 30, 30), null));
        String b = reference(create(officer, area.rect(10, 0, 40, 30), null));

        JsonNode moved = json.readTree(correct(officer, b, 1, area.rect(100, 0, 130, 30)).getContentAsString());
        assertThat(moved.path("alerts").size()).as("no longer overlapping").isZero();
        JsonNode listed = json.readTree(officer.browser().send(get(PARCELS + "/" + a + "/alerts")).getContentAsString());
        assertThat(listed.get(0).path("status").asString("")).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_ref = ? AND action = 'CONFLICT_ALERT_SUPERSEDED'",
                Long.class, b)).isEqualTo(1);

        JsonNode back = json.readTree(correct(officer, b, 2, area.rect(15, 0, 45, 30)).getContentAsString());
        assertThat(back.path("alerts").get(0).path("type").asString("")).isEqualTo("OVERLAP");
        JsonNode again = json.readTree(officer.browser().send(get(PARCELS + "/" + a + "/alerts")).getContentAsString());
        assertThat(again.get(0).path("status").asString("")).as("open alerts first").isEqualTo("OPEN");
        assertThat(again.get(0).path("otherVersionNo").asInt()).isEqualTo(3);
    }

    @Test
    void theThresholdsComeFromTheSettingsAndAChangeIsAudited() throws Exception {
        try {
            assertThat(SystemActor.call("conflict-policy",
                    () -> policy.apply(new ConflictProperties(new BigDecimal("900.00"), new BigDecimal("0.95"))))).isTrue();
            assertThat(jdbc.queryForObject("SELECT details->>'minOverlapM2' FROM audit_event WHERE action = 'CONFLICT_POLICY_CHANGED'"
                    + " ORDER BY chain_pos DESC LIMIT 1", String.class)).isEqualTo("900.00");

            SignedIn officer = signedIn("CADASTRAL_OFFICER");
            Area area = Area.random();
            create(officer, area.rect(0, 0, 30, 30), null);
            assertThat(json.readTree(create(officer, area.rect(10, 0, 40, 30), null).getContentAsString())
                    .path("alerts").size()).as("600 m2 is below a 900 m2 minimum").isZero();
            assertThat(SystemActor.call("conflict-policy",
                    () -> policy.apply(new ConflictProperties(new BigDecimal("900"), new BigDecimal("0.950"))))).as("same values").isFalse();
        } finally {
            SystemActor.call("conflict-policy", () -> policy.apply(new ConflictProperties(new BigDecimal("1.00"), new BigDecimal("0.95"))));
        }
    }

    private MockHttpServletResponse create(SignedIn officer, String geometry, String externalReference) throws Exception {
        MockHttpServletResponse response = sendJson(officer.browser(), post(PARCELS).header("Idempotency-Key", "it-" + UUID.randomUUID()),
                "{\"officeCode\":\"DEMOIT\",\"locality\":\"Conflict fixture\",\"srid\":32633,"
                        + (externalReference == null ? "" : "\"externalReference\":\"" + externalReference + "\",")
                        + "\"geometry\":" + geometry + "}");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return response;
    }

    private MockHttpServletResponse correct(SignedIn officer, String reference, int expected, String geometry) throws Exception {
        MockHttpServletResponse response = sendJson(officer.browser(), post(PARCELS + "/" + reference + "/versions"),
                "{\"expectedVersion\":" + expected + ",\"srid\":32633,\"geometry\":" + geometry
                        + ",\"reason\":\"Boundary re-surveyed on site\"}");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return response;
    }

    private String reference(MockHttpServletResponse created) throws Exception {
        return json.readTree(created.getContentAsString()).path("reference").asString("");
    }
}
