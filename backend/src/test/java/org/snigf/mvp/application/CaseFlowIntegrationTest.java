// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.snigf.mvp.document.DocumentFixtures;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.payment.infrastructure.provider.InProcessSimulatorChannel;
import org.snigf.mvp.payment.infrastructure.provider.SimulatorChannel;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A case through the real endpoints, as the integration tests of the later stages need it: an
 * applicant, a parcel at the test office, a submitted application, its DEMO payment (signed
 * callbacks through the real endpoint, E-17), its acceptance, and the review actions (E-14, E-15,
 * E-16).
 */
public abstract class CaseFlowIntegrationTest extends IdentityIntegrationTest {

    protected static final String APPS = "/api/v1/applications";
    protected static final String REVIEWS = "/api/v1/reviews";
    protected static final String CALLBACK = "/api/v1/payments/callbacks/demo-simulator";
    /** The simulator's certificate subject, as the proxy's provider listener writes it. */
    protected static final String SIMULATOR_SUBJECT = "CN=snigf-payment-sim,O=FLYT";
    protected static final long FEE = 25_000;

    @Autowired
    protected KeyService keys;

    @Autowired
    private SimulatorChannel channel;

    /** The in-process simulator of the test profile: the orders it took. */
    protected InProcessSimulatorChannel simulator() {
        return (InProcessSimulatorChannel) channel;
    }

    /** Draft, both required documents, submitted: in completeness review. */
    protected String submitted(Browser applicant, String parcel) throws Exception {
        String id = json.readTree(sendJson(applicant, post(APPS).header("Idempotency-Key", key()),
                "{\"typeCode\":\"DEMO_REGISTRATION\",\"data\":{}}").getContentAsString()).path("id").asString("");
        sendJson(applicant, put(APPS + "/" + id), json.writeValueAsString(Map.of("expectedVersion", 0, "parcelReference", parcel,
                "data", Map.of("applicantCapacity", "OWNER", "landUse", "RESIDENTIAL", "statement", "Built in 2015 (synthetic)"))));
        for (String category : List.of("DEMO_IDENTITY", "DEMO_PROOF_OF_RIGHT")) {
            applicant.csrf();
            assertThat(applicant.send(multipart(APPS + "/" + id + "/documents/" + category)
                    .file(new MockMultipartFile("file", category + ".pdf", "application/pdf", DocumentFixtures.pdf("/Lang (" + category + ")")))
                    .header("Idempotency-Key", key())).getStatus()).isEqualTo(201);
        }
        assertThat(sendJson(applicant, post(APPS + "/" + id + "/submission").header("Idempotency-Key", key()),
                "{\"expectedVersion\":1}").getStatus()).isEqualTo(200);
        return id;
    }

    /** Starts a DEMO payment as the applicant, with a fresh {@code Idempotency-Key}. */
    protected MockHttpServletResponse pay(Browser applicant, String id, String scenario) throws Exception {
        return pay(applicant, id, scenario, key());
    }

    /** Starts a DEMO payment; a null key leaves the header out. */
    protected MockHttpServletResponse pay(Browser applicant, String id, String scenario, String idempotencyKey)
            throws Exception {
        MockHttpServletRequestBuilder request = post(APPS + "/" + id + "/payments");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return sendJson(applicant, request, "{\"scenario\":\"" + scenario + "\"}");
    }

    /** Pays the DEMO fee: the charge, then the simulator's signed success callback. Returns the reference. */
    protected String paid(Browser applicant, String id) throws Exception {
        MockHttpServletResponse started = pay(applicant, id, "SUCCEED");
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(200);
        String reference = json.readTree(started.getContentAsString()).path("current").path("reference").asString("");
        InProcessSimulatorChannel.Order order = simulator().last(reference);
        MockHttpServletResponse called = callback(order.transactionId(), reference, order.order().amount(), "SUCCEEDED");
        assertThat(called.getStatus()).as(called.getContentAsString()).isEqualTo(200);
        return reference;
    }

    /** A callback as the simulator sends it: signed with the callback key, through the provider listener. */
    protected MockHttpServletResponse callback(String transactionId, String reference, long amount, String status)
            throws Exception {
        String body = callbackBody(transactionId, reference, amount, status);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return callback(body, timestamp, signature(timestamp, body), SIMULATOR_SUBJECT);
    }

    protected String callbackBody(String transactionId, String reference, long amount, String status) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "DEMO_SIMULATOR");
        body.put("transactionId", transactionId);
        body.put("reference", reference);
        body.put("amount", amount);
        body.put("currency", "XAF");
        body.put("status", status);
        body.put("occurredAt", Instant.now().toString());
        body.put("demo", true);
        return json.writeValueAsString(body);
    }

    /** {@code v<version>=<base64>} over {@code <timestamp>.<body>}, as the simulator signs in key custody. */
    protected String signature(String timestamp, String body) {
        Mac mac = keys.mac(MacKey.PAYMENT_CALLBACK, (timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v" + mac.keyVersion() + "=" + Base64.getEncoder().encodeToString(mac.value());
    }

    /** Any callback; a null argument leaves its header out. No session, no CSRF token: a provider is no browser. */
    protected MockHttpServletResponse callback(String body, String timestamp, String signature, String subject)
            throws Exception {
        MockHttpServletRequestBuilder request = post(CALLBACK).contentType(MediaType.APPLICATION_JSON).content(body);
        if (timestamp != null) {
            request.header("X-Snigf-Timestamp", timestamp);
        }
        if (signature != null) {
            request.header("X-Snigf-Signature", signature);
        }
        if (subject != null) {
            request.header("X-Snigf-Provider-Client", subject);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    /** Accepted as complete by an intake officer of the test office: under review. The fee must be paid. */
    protected void accepted(SignedIn intake, String id) throws Exception {
        long version = json.readTree(intake.browser().send(get("/api/v1/cases/" + id)).getContentAsString()).path("version").asLong();
        MockHttpServletResponse accepted = sendJson(intake.browser(), post("/api/v1/cases/" + id + "/acceptance"),
                "{\"expectedVersion\":" + version + "}");
        assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(200);
    }

    /** Draft, both required documents, submitted, paid, accepted by an intake officer: under review. */
    protected String underReview(Browser applicant, String parcel) throws Exception {
        String id = submitted(applicant, parcel);
        paid(applicant, id);
        accepted(signedIn("INTAKE_OFFICER"), id);
        return id;
    }

    /** Resolves every open alert on the parcel, as a cadastral officer who authored neither version. */
    protected void resolveAlerts(String parcelReference) throws Exception {
        SignedIn resolver = signedIn("CADASTRAL_OFFICER");
        JsonNode alerts = json.readTree(resolver.browser().send(get("/api/v1/parcels/" + parcelReference + "/alerts")).getContentAsString());
        for (JsonNode alert : alerts) {
            if (alert.path("status").asString("").equals("OPEN")) {
                long version = json.readTree(resolver.browser().send(get("/api/v1/alerts/" + alert.path("id").asString("")))
                        .getContentAsString()).path("version").asLong();
                assertThat(sendJson(resolver.browser(), post("/api/v1/alerts/" + alert.path("id").asString("") + "/resolution"),
                        json.writeValueAsString(Map.of("expectedVersion", version, "resolution", "DATA_ERROR_CORRECTED",
                                "note", "Neighbour boundary surveyed again"))).getStatus()).isEqualTo(200);
            }
        }
    }

    /** A review action ({@code review-claim}, {@code recommendation}, {@code decision} ...) on a case. */
    protected MockHttpServletResponse act(SignedIn who, String id, String action, String json) throws Exception {
        return sendJson(who.browser(), post(REVIEWS + "/" + id + "/" + action), json);
    }

    protected long version(SignedIn who, String id) throws Exception {
        return json.readTree(who.browser().send(get(REVIEWS + "/" + id)).getContentAsString()).path("version").asLong();
    }

    protected String body(Object... pairs) throws Exception {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(map);
    }

    protected Browser applicant() throws Exception {
        return applicant("Demo Applicant");
    }

    protected Browser applicant(String displayName) throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, displayName, PASSWORD);
        browser.login(email, PASSWORD);
        browser.csrf();
        return browser;
    }

    /** Adds a role at the test office directly in the database (bypassing the four-eyes workflow). */
    protected void addRole(UUID user, String role) throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.PreparedStatement insert = su.prepareStatement("INSERT INTO snigf.role_assignment"
                     + " (id, user_id, role, office_id, grant_reason) SELECT gen_random_uuid(), ?, ?, id, 'test seed'"
                     + " FROM snigf.office WHERE code = 'DEMOIT'")) {
            insert.setObject(1, user);
            insert.setString(2, role);
            insert.executeUpdate();
            su.commit();
        }
    }

    protected static long[] area() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new long[] {300_000 + r.nextLong(0, 200) * 1000, 500_000 + r.nextLong(0, 300) * 1000};
    }

    /** A 30 m square at the test office, {@code dx} metres east of the area's origin. */
    protected String parcel(SignedIn officer, long[] area, double dx) throws Exception {
        return parcel(officer, area, dx, "DEMOIT");
    }

    /** A 30 m square at {@code office} (the officer's), {@code dx} metres east of the area's origin. */
    protected String parcel(SignedIn officer, long[] area, double dx, String office) throws Exception {
        double e = area[0] + dx;
        double n = area[1];
        String square = "{\"type\":\"Polygon\",\"coordinates\":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}".formatted(
                e, n, e + 30, n, e + 30, n + 30, e, n + 30, e, n);
        MockHttpServletResponse response = sendJson(officer.browser(), post("/api/v1/parcels").header("Idempotency-Key", key()),
                "{\"officeCode\":\"" + office + "\",\"locality\":\"Review fixture\",\"srid\":32633,\"geometry\":" + square + "}");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).path("reference").asString("");
    }

    protected String code(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("code").asString("");
    }

    protected static String key() {
        return "it-" + UUID.randomUUID();
    }
}
