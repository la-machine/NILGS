// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MVP-04 / E-11 (Slice 1 design C6b): an alert is reviewed by office and resolved, with a note,
 * by a cadastral officer who authored neither compared version. The author is refused by the
 * service and, if the service were bypassed, by the database (V12). Each test works in its own
 * random area (UTM 33N metres).
 */
class AlertResolutionSoDIT extends IdentityIntegrationTest {

    static final String PARCELS = "/api/v1/parcels";
    static final String ALERTS = "/api/v1/alerts";
    static final String NOTE = "Checked on site with both owners: the boundaries agree";

    @Test
    void theAuthorCannotResolveAnotherOfficerDoesWithANoteOnceAndEverythingIsAudited() throws Exception {
        SignedIn author = signedIn("CADASTRAL_OFFICER");
        long[] area = area();
        String a = reference(create(author, "DEMOIT", rect(area, 0, 30)));
        String b = reference(create(author, "DEMOIT", rect(area, 10, 40)));

        JsonNode open = json.readTree(author.browser().send(get(ALERTS).param("office", "DEMOIT").param("status", "OPEN"))
                .getContentAsString());
        JsonNode listed = find(open, a, b);
        assertThat(listed).as("the office list shows the new alert").isNotNull();
        UUID id = UUID.fromString(listed.path("id").asString(""));

        JsonNode detail = read(author, id);
        assertThat(detail.path("resolverIsAuthor").asBoolean()).isTrue();
        assertThat(detail.path("display").path("srid").asInt()).isEqualTo(32633);
        assertThat(detail.path("display").path("a").path("type").asString("")).isEqualTo("MultiPolygon");
        assertThat(detail.path("display").path("intersection").path("type").asString("")).isEqualTo("MultiPolygon");
        assertThat(detail.path("raisedByReference").asString("")).isEqualTo(b);
        long version = detail.path("version").asLong();

        MockHttpServletResponse byAuthor = resolve(author, id, version, "NO_CONFLICT", NOTE);
        assertThat(byAuthor.getStatus()).isEqualTo(403);
        assertThat(code(byAuthor)).isEqualTo("RESOLVER_IS_AUTHOR");
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_event WHERE entity_id = ? AND action = 'CONFLICT_ALERT_RESOLVE_REJECTED'",
                String.class, id)).isEqualTo("RESOLVER_IS_AUTHOR");

        SignedIn colleague = signedIn("CADASTRAL_OFFICER");
        assertThat(read(colleague, id).path("resolverIsAuthor").asBoolean()).isFalse();
        MockHttpServletResponse noNote = resolve(colleague, id, version, "NO_CONFLICT", "  short  ");
        assertThat(noNote.getStatus()).isEqualTo(422);
        assertThat(code(noNote)).isEqualTo("NOTE_REQUIRED");
        MockHttpServletResponse tooLong = resolve(colleague, id, version, "NO_CONFLICT", "x".repeat(2001));
        assertThat(code(tooLong)).isEqualTo("NOTE_TOO_LONG");
        MockHttpServletResponse stale = resolve(colleague, id, version + 1, "NO_CONFLICT", NOTE);
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("STALE_VERSION");

        MockHttpServletResponse done = resolve(colleague, id, version, "REFERRED_FOR_ADJUDICATION", NOTE);
        assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
        JsonNode resolved = json.readTree(done.getContentAsString());
        assertThat(resolved.path("status").asString("")).isEqualTo("RESOLVED");
        assertThat(resolved.path("resolution").asString("")).isEqualTo("REFERRED_FOR_ADJUDICATION");
        assertThat(resolved.path("resolutionNote").asString("")).isEqualTo(NOTE);
        assertThat(resolved.path("resolvedAt").isNull()).isFalse();
        assertThat(jdbc.queryForObject("SELECT actor_id || ':' || (details->>'resolution') FROM audit_event"
                + " WHERE entity_id = ? AND action = 'CONFLICT_ALERT_RESOLVED'", String.class, id))
                .isEqualTo(colleague.id() + ":REFERRED_FOR_ADJUDICATION");

        MockHttpServletResponse again = resolve(colleague, id, resolved.path("version").asLong(), "NO_CONFLICT", NOTE);
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("ALERT_NOT_OPEN");
    }

    @Test
    void onlyReviewersAtAnOfficeOfEitherParcelReachTheAlert() throws Exception {
        SignedIn author = signedIn("CADASTRAL_OFFICER");
        long[] area = area();
        String a = reference(create(author, "DEMOIT", rect(area, 0, 30)));
        String b = reference(create(author, "DEMOIT", rect(area, 10, 40)));
        UUID id = alertId(author, "DEMOIT", a, b);

        SignedIn elsewhere = signedIn("CADASTRAL_OFFICER", "DEMOIT2");
        assertThat(elsewhere.browser().send(get(ALERTS + "/" + id)).getStatus()).isEqualTo(403);
        assertThat(resolve(elsewhere, id, 0, "NO_CONFLICT", NOTE).getStatus()).isEqualTo(403);
        assertThat(elsewhere.browser().send(get(ALERTS).param("office", "DEMOIT")).getStatus()).isEqualTo(403);
        SignedIn reviewer = signedIn("REVIEWER");
        assertThat(reviewer.browser().send(get(ALERTS + "/" + id)).getStatus())
                .as("reading parcels is not reviewing alerts").isEqualTo(403);
        assertThat(author.browser().send(get(ALERTS + "/" + UUID.randomUUID())).getStatus())
                .as("an unknown alert has no office: refused like any other").isEqualTo(403);
    }

    @Test
    void anAlertBetweenTwoOfficesIsResolvedFromEither() throws Exception {
        SignedIn here = signedIn("CADASTRAL_OFFICER");
        SignedIn there = signedIn("CADASTRAL_OFFICER", "DEMOIT2");
        long[] area = area();
        String a = reference(create(here, "DEMOIT", rect(area, 0, 30)));
        String b = reference(create(there, "DEMOIT2", rect(area, 10, 40)));
        UUID id = alertId(here, "DEMOIT", a, b);
        assertThat(find(json.readTree(there.browser().send(get(ALERTS).param("office", "DEMOIT2")).getContentAsString()), a, b))
                .as("listed at both offices").isNotNull();

        SignedIn colleagueThere = signedIn("CADASTRAL_OFFICER", "DEMOIT2");
        long version = read(colleagueThere, id).path("version").asLong();
        MockHttpServletResponse done = resolve(colleagueThere, id, version, "NO_CONFLICT", NOTE);
        assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    }

    @Test
    void theDatabaseRefusesTheAuthorEvenWhenTheServiceIsBypassed() throws Exception {
        SignedIn author = signedIn("CADASTRAL_OFFICER");
        long[] area = area();
        String a = reference(create(author, "DEMOIT", rect(area, 0, 30)));
        String b = reference(create(author, "DEMOIT", rect(area, 10, 40)));
        UUID id = alertId(author, "DEMOIT", a, b);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE conflict_alert SET status = 'RESOLVED', resolution = 'NO_CONFLICT', resolution_note = ?,
                       resolved_by = ?, resolved_at = now(), version = version + 1 WHERE id = ?""", NOTE, author.id(), id))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("the author of a compared version cannot resolve");
    }

    private JsonNode read(SignedIn who, UUID id) throws Exception {
        MockHttpServletResponse response = who.browser().send(get(ALERTS + "/" + id));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private MockHttpServletResponse resolve(SignedIn who, UUID id, long version, String resolution, String note) throws Exception {
        return sendJson(who.browser(), post(ALERTS + "/" + id + "/resolution"), json.writeValueAsString(
                java.util.Map.of("expectedVersion", version, "resolution", resolution, "note", note)));
    }

    private UUID alertId(SignedIn who, String office, String a, String b) throws Exception {
        JsonNode listed = find(json.readTree(who.browser().send(get(ALERTS).param("office", office)).getContentAsString()), a, b);
        assertThat(listed).isNotNull();
        return UUID.fromString(listed.path("id").asString(""));
    }

    /** The alert between these two parcels in a list, or null. */
    private static JsonNode find(JsonNode list, String a, String b) {
        for (JsonNode alert : list) {
            String x = alert.path("a").path("reference").asString("");
            String y = alert.path("b").path("reference").asString("");
            if ((x.equals(a) && y.equals(b)) || (x.equals(b) && y.equals(a))) {
                return alert;
            }
        }
        return null;
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("code").asString("");
    }

    private static long[] area() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new long[] {300_000 + r.nextLong(0, 200) * 1000, 500_000 + r.nextLong(0, 300) * 1000};
    }

    /** A 30 m tall rectangle from x0 to x1 (metres east of the area's origin). */
    private static String rect(long[] area, double x0, double x1) {
        double e = area[0];
        double n = area[1];
        return """
                {"type":"Polygon","coordinates":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}""".formatted(
                e + x0, n, e + x1, n, e + x1, n + 30, e + x0, n + 30, e + x0, n);
    }

    private MockHttpServletResponse create(SignedIn officer, String office, String geometry) throws Exception {
        MockHttpServletResponse response = sendJson(officer.browser(), post(PARCELS).header("Idempotency-Key", "it-" + UUID.randomUUID()),
                "{\"officeCode\":\"" + office + "\",\"locality\":\"Resolution fixture\",\"srid\":32633,\"geometry\":" + geometry + "}");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return response;
    }

    private String reference(MockHttpServletResponse created) throws Exception {
        return json.readTree(created.getContentAsString()).path("reference").asString("");
    }
}
