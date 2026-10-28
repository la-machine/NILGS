// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MVP-01 / E-05 through the real endpoints: creation from GeoJSON with server-issued
 * references, PostGIS validation with each refusal reason, CRS equivalence, idempotent
 * retries, versioned corrections, and office-scoped permissions, with their audit events.
 */
class ParcelApiIT extends IdentityIntegrationTest {

    static final String PARCELS = "/api/v1/parcels";
    /** About 30 m × 30 m in Bastos, Yaoundé (UTM 32N side of 12°E). */
    static final String SQUARE = """
            {"type":"Polygon","coordinates":[[[11.5000,3.8700],[11.50027,3.8700],[11.50027,3.87027],[11.5000,3.87027],[11.5000,3.8700]]]}""";

    @Test
    void anOfficerCreatesAParcelWithAServerReferenceAndItIsAudited() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        MockHttpServletResponse created = create(officer, key(), "DEMOIT", "Bastos, Yaoundé", 4326, SQUARE);
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        JsonNode body = json.readTree(created.getContentAsString());
        String reference = body.path("reference").asString("");
        assertThat(reference).matches("^DEMO-PCL-DEMOIT-\\d{4}-\\d{6}-[0-9A-Z]$");
        assertThat(body.path("versionNo").asInt()).isEqualTo(1);
        assertThat(body.path("areaM2").asDouble()).isBetween(850.0, 950.0);
        assertThat(body.path("alerts").isArray()).isTrue();
        assertThat(created.getHeader("Location")).isEqualTo(PARCELS + "/" + reference);

        JsonNode read = json.readTree(officer.browser().send(get(PARCELS + "/" + reference)).getContentAsString());
        assertThat(read.path("officeCode").asString("")).isEqualTo("DEMOIT");
        assertThat(read.path("current").path("geometry").path("type").asString("")).isEqualTo("MultiPolygon");
        assertThat(read.path("current").path("display").path("srid").asInt()).isEqualTo(32632);
        assertThat(read.path("current").path("display").path("geometry").path("coordinates").get(0).get(0).get(0).get(0)
                .asDouble()).as("UTM easting in metres").isGreaterThan(100_000);
        assertThat(read.path("current").path("verificationStatus").asString("")).isEqualTo("UNVERIFIED");

        assertThat(jdbc.queryForObject("SELECT action || ':' || outcome || ':' || actor_id FROM audit_event"
                + " WHERE entity_ref = ? ORDER BY chain_pos LIMIT 1", String.class, reference))
                .isEqualTo("PARCEL_CREATED:SUCCESS:" + officer.id());
    }

    @Test
    void eachRefusedGeometryNamesItsReasonAndNothingIsStored() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        long before = parcels();
        String bowtie = """
                {"type":"Polygon","coordinates":[[[11.5,3.87],[11.5003,3.8703],[11.5003,3.87],[11.5,3.8703],[11.5,3.87]]]}""";
        assertRefused(create(officer, key(), "DEMOIT", "Bastos", 4326, bowtie), "GEOMETRY_INVALID");
        MockHttpServletResponse invalid = create(officer, key(), "DEMOIT", "Bastos", 4326, bowtie);
        assertThat(json.readTree(invalid.getContentAsString()).path("reason").asString("")).contains("Self-intersection");
        assertRefused(create(officer, key(), "DEMOIT", "Bastos", 4326, "{\"type\":\"Point\",\"coordinates\":[11.5,3.87]}"),
                "GEOMETRY_TYPE_UNSUPPORTED");
        assertRefused(create(officer, key(), "DEMOIT", "Bastos", 3857, SQUARE), "CRS_UNSUPPORTED");
        assertRefused(create(officer, key(), "DEMOIT", "Bastos", null, SQUARE), "CRS_MISSING");
        assertRefused(create(officer, key(), "DEMOIT", "Paris", 4326, """
                {"type":"Polygon","coordinates":[[[2.35,48.85],[2.351,48.85],[2.351,48.851],[2.35,48.851],[2.35,48.85]]]}"""),
                "GEOMETRY_OUT_OF_BOUNDS");
        assertRefused(create(officer, key(), "DEMOIT", "Swapped", 4326, """
                {"type":"Polygon","coordinates":[[[3.8700,11.5000],[3.8700,11.50027],[3.87027,11.50027],[3.87027,11.5000],[3.8700,11.5000]]]}"""),
                "GEOMETRY_OUT_OF_BOUNDS");
        assertRefused(create(officer, key(), "DEMOIT", "Bastos", 4326, SQUARE.replace("\"type\":\"Polygon\"",
                "\"type\":\"Polygon\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:4326\"}}")), "GEOMETRY_MALFORMED");
        assertRefused(create(officer, key(), "DEMOIT", "Bastos", 4326, """
                {"type":"Polygon","coordinates":[[[11.5,3.87],[11.5003,3.87],[11.5003,3.8703]]]}"""), "GEOMETRY_INVALID");
        assertThat(parcels()).as("nothing stored").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'PARCEL_CREATE_REJECTED'"
                + " AND outcome = 'FAILED' AND actor_id = ?", Long.class, officer.id())).isEqualTo(9);
    }

    @Test
    void utmAndLatitudeLongitudeInputStoreTheSameGeometryWithinOneCentimetre() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        String wgs84 = """
                {"type":"Polygon","coordinates":[[[13.4000,9.3000],[13.4004,9.3000],[13.4004,9.3004],[13.4000,9.3004],[13.4000,9.3000]]]}""";
        // Option 0: no "crs" member, which the API refuses (RFC 7946; the CRS travels in srid).
        String utm33 = jdbc.queryForObject("SELECT ST_AsGeoJSON(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), 32633), 4, 0)",
                String.class, wgs84);
        String a = reference(create(officer, key(), "DEMOIT", "Garoua", 4326, wgs84));
        String b = reference(create(officer, key(), "DEMOIT", "Garoua", 32633, utm33));
        Double distance = jdbc.queryForObject("""
                SELECT ST_HausdorffDistance(ST_Transform(va.geom, 32633), ST_Transform(vb.geom, 32633))
                  FROM parcel pa JOIN parcel_version va ON va.parcel_id = pa.id
                  JOIN parcel pb ON pb.reference = ? JOIN parcel_version vb ON vb.parcel_id = pb.id
                 WHERE pa.reference = ?""", Double.class, b, a);
        assertThat(distance).as("metres").isLessThan(0.01);
        JsonNode read = json.readTree(officer.browser().send(get(PARCELS + "/" + b)).getContentAsString());
        assertThat(read.path("current").path("sourceSrid").asInt()).isEqualTo(32633);
        assertThat(read.path("current").path("display").path("srid").asInt()).as("east of 12°E").isEqualTo(32633);
    }

    @Test
    void aRetryWithTheSameKeyCreatesNothingNew() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        String key = key();
        MockHttpServletResponse first = create(officer, key, "DEMOIT", "Mvog-Ada", 4326, SQUARE);
        long after = parcels();
        MockHttpServletResponse again = create(officer, key, "DEMOIT", "Mvog-Ada", 4326, SQUARE);
        assertThat(again.getStatus()).isEqualTo(201);
        assertThat(again.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(reference(again)).isEqualTo(reference(first));
        assertThat(parcels()).isEqualTo(after);
        assertRefused(create(officer, key, "DEMOIT", "Another place", 4326, SQUARE), "IDEMPOTENCY_KEY_REUSED");
        assertRefused(create(officer, null, "DEMOIT", "Mvog-Ada", 4326, SQUARE), "IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void aCorrectionAddsAVersionAndKeepsTheFirstReadable() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        String reference = reference(create(officer, key(), "DEMOIT", "Etoudi", 4326, SQUARE));
        String bigger = SQUARE.replace("11.50027", "11.50040");

        assertRefused(correct(officer, reference, 1, bigger, "too short"), "REASON_REQUIRED");
        MockHttpServletResponse corrected = correct(officer, reference, 1, bigger, "Boundary surveyed again on site");
        assertThat(corrected.getStatus()).as(corrected.getContentAsString()).isEqualTo(201);
        assertThat(json.readTree(corrected.getContentAsString()).path("versionNo").asInt()).isEqualTo(2);
        MockHttpServletResponse stale = correct(officer, reference, 1, bigger, "A second officer on stale data");
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(stale.getContentAsString()).contains("STALE_VERSION");

        JsonNode read = json.readTree(officer.browser().send(get(PARCELS + "/" + reference)).getContentAsString());
        assertThat(read.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(read.path("versions").size()).isEqualTo(2);
        assertThat(read.path("current").path("locality").asString("")).as("kept").isEqualTo("Etoudi");
        JsonNode first = json.readTree(officer.browser().send(get(PARCELS + "/" + reference + "/versions/1"))
                .getContentAsString());
        assertThat(first.path("areaM2").asDouble()).isLessThan(read.path("current").path("areaM2").asDouble());
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_event WHERE action = 'PARCEL_CORRECTED' AND entity_ref = ?",
                String.class, reference)).isEqualTo("Boundary surveyed again on site");
    }

    @Test
    void permissionsFollowTheParcelsOffice() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        SignedIn elsewhere = signedIn("CADASTRAL_OFFICER", "DEMOIT2");
        SignedIn reviewer = signedIn("REVIEWER");
        String reference = reference(create(officer, key(), "DEMOIT", "Nlongkak", 4326, SQUARE));

        assertThat(create(elsewhere, key(), "DEMOIT", "Not my office", 4326, SQUARE).getStatus()).isEqualTo(403);
        assertThat(correct(elsewhere, reference, 1, SQUARE, "Not my office to correct").getStatus()).isEqualTo(403);
        assertThat(elsewhere.browser().send(get(PARCELS + "/" + reference)).getStatus()).as("parcel.read everywhere")
                .isEqualTo(200);
        assertThat(reviewer.browser().send(get(PARCELS + "/" + reference)).getStatus()).isEqualTo(200);
        assertThat(create(reviewer, key(), "DEMOIT", "Reviewers do not create", 4326, SQUARE).getStatus()).isEqualTo(403);
        assertThat(reviewer.browser().send(get(PARCELS + "/DEMO-PCL-DEMOIT-2026-999999-" + "0")).getStatus())
                .as("malformed: not found, nothing leaked").isEqualTo(404);
        assertThat(json.readTree(reviewer.browser().send(get(PARCELS).param("office", "DEMOIT").param("q", "Nlongkak"))
                .getContentAsString()).findValuesAsString("reference")).contains(reference);

        String email = email();
        Browser applicant = new Browser();
        applicant.register(email, "Applicant", PASSWORD);
        applicant.login(email, PASSWORD);
        assertThat(applicant.send(get(PARCELS + "/" + reference)).getStatus()).as("linked scope is not everywhere")
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'ACCESS_DENIED' AND actor_id = ?",
                Long.class, elsewhere.id())).isGreaterThanOrEqualTo(2);
    }

    private MockHttpServletResponse create(SignedIn officer, String key, String office, String locality, Integer srid,
                                           String geometry) throws Exception {
        MockHttpServletRequestBuilder request = post(PARCELS);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return sendJson(officer.browser(), request, "{\"officeCode\":\"" + office + "\",\"locality\":\"" + locality + "\","
                + (srid == null ? "" : "\"srid\":" + srid + ",") + "\"geometry\":" + geometry + "}");
    }

    private MockHttpServletResponse correct(SignedIn officer, String reference, int expected, String geometry,
                                            String reason) throws Exception {
        return sendJson(officer.browser(), post(PARCELS + "/" + reference + "/versions"),
                "{\"expectedVersion\":" + expected + ",\"srid\":4326,\"geometry\":" + geometry + ",\"reason\":\"" + reason + "\"}");
    }

    private String reference(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).path("reference").asString("");
    }

    private long parcels() {
        return jdbc.queryForObject("SELECT count(*) FROM parcel", Long.class);
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static void assertRefused(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
    }
}
