// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.document.DocumentFixtures;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR-041 step 2 through the real endpoints: the public request; verification by one administrator
 * with evidence and offices; approval by another, never the verifier, only with two keys; suspension by
 * one; reinstatement, revocation and office changes by two; expiry; evidence for verifiers and
 * approvers only; nobody else reaches any of it; a professional's lost keys reset by a supervisor of
 * their office. Each step audited with the professional's capacity.
 */
class ProfessionalLifecycleIT extends IdentityIntegrationTest {

    private static final String ADMIN = "/api/v1/admin/professionals";
    private static final String REQUESTS = "/api/v1/admin/professional-requests";

    private record Notary(UUID id, String email, Browser browser) {
    }

    private String body(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(map);
    }

    private MockHttpServletResponse publicRequest(Browser browser, String email, String registration) throws Exception {
        browser.csrf();
        return browser.send(json(post("/api/v1/professional-requests"), body("email", email, "displayName", "DEMO Notary", "password", PASSWORD,
                "preferredLanguage", "fr", "profession", "NOTARY", "chamber", "DEMO Chamber", "registrationNumber", registration,
                "jurisdiction", "DEMO Yaounde")));
    }

    /** A professional who requested an account and enrolled two keys. */
    private Notary requestedNotary() throws Exception {
        String email = email();
        Browser browser = new Browser();
        assertThat(publicRequest(browser, email, "DEMO-N-" + UUID.randomUUID().toString().substring(0, 8)).getStatus()).isEqualTo(201);
        browser.login(email, PASSWORD);
        register(browser, new Authenticator(), "Office key");
        register(browser, new Authenticator(), "Backup key");
        browser.csrf();
        return new Notary(userId(email), email, browser);
    }

    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        String text = response.getContentAsString();
        return text.isEmpty() ? json.createObjectNode() : json.readTree(text);
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("code").asString("");
    }

    private MockHttpServletResponse verify(SignedIn admin, UUID notary, List<String> offices) throws Exception {
        return sendJson(admin.browser(), post(ADMIN + "/" + notary + "/verification"), body("body", "DEMO Chamber of Notaries",
                "reference", "DEMO-REF-1", "checkedOn", java.time.LocalDate.now().toString(), "note", "Credential confirmed by the DEMO chamber",
                "offices", offices));
    }

    private MockHttpServletResponse decide(SignedIn admin, String request, boolean approve, String reason) throws Exception {
        return sendJson(admin.browser(), post(REQUESTS + "/" + request + (approve ? "/approval" : "/rejection")),
                reason == null ? "{}" : body("reason", reason));
    }

    private MockHttpServletResponse change(SignedIn admin, UUID notary, String kind, List<String> add, List<String> remove, String reason) throws Exception {
        return sendJson(admin.browser(), post(ADMIN + "/" + notary + "/requests"), body("kind", kind, "officesAdd", add, "officesRemove", remove,
                "reason", reason));
    }

    private JsonNode me(Notary notary) throws Exception {
        return ok(notary.browser().send(get("/api/v1/professional/me")), 200);
    }

    private String activated(SignedIn verifier, SignedIn approver, Notary notary) throws Exception {
        String request = ok(verify(verifier, notary.id(), List.of("DEMOIT")), 201).path("id").asString("");
        ok(decide(approver, request, true, null), 200);
        return request;
    }

    // --- Tests ------------------------------------------------------------------------------------------

    @Test
    void aNotaryIsVerifiedByOneAdministratorAndApprovedByAnother() throws Exception {
        Notary notary = requestedNotary();
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        assertThat(me(notary).path("profile").path("status").asString("")).isEqualTo("REQUESTED");
        assertThat(notary.browser().session().getContentAsString()).as("no permission yet").contains("\"permissions\":[]");

        assertThat(code(verify(first, notary.id(), List.of("NOWHERE")))).isEqualTo("OFFICES_INVALID");
        String request = ok(verify(first, notary.id(), List.of("DEMOIT")), 201).path("id").asString("");
        first.browser().csrf();
        JsonNode evidence = ok(first.browser().send(multipart(ADMIN + "/" + notary.id() + "/verification/documents")
                .file(new MockMultipartFile("file", "letter.pdf", "application/pdf", DocumentFixtures.pdf("/Title (chamber letter)")))
                .header("Idempotency-Key", UUID.randomUUID().toString())), 201);
        first.browser().csrf();
        assertThat(code(first.browser().send(multipart(ADMIN + "/" + notary.id() + "/verification/documents")
                .file(new MockMultipartFile("file", "page.pdf", "application/pdf", DocumentFixtures.html())).header("Idempotency-Key", UUID.randomUUID().toString()))))
                .as("the same checks as every document").isEqualTo("TYPE_NOT_ALLOWED");

        MockHttpServletResponse own = decide(first, request, true, null);
        assertThat(own.getStatus()).isEqualTo(403);
        assertThat(code(own)).isEqualTo("FOUR_EYES");
        JsonNode decided = ok(decide(second, request, true, null), 200);
        assertThat(decided.path("status").asString("")).isEqualTo("APPROVED");

        JsonNode profile = me(notary).path("profile");
        assertThat(profile.path("status").asString("")).isEqualTo("ACTIVE");
        assertThat(profile.path("offices")).extracting(n -> n.asString("")).containsExactly("DEMOIT");

        JsonNode detail = ok(second.browser().send(get(ADMIN + "/" + notary.id())), 200);
        assertThat(detail.path("verification").path("reference").asString("")).isEqualTo("DEMO-REF-1");
        String document = evidence.path("id").asString("");
        assertThat(detail.path("verification").path("documents").get(0).has("storageKey")).isFalse();
        assertThat(second.browser().send(get(ADMIN + "/" + notary.id() + "/verification/documents/" + document + "/content")).getStatus()).isEqualTo(200);

        for (String action : List.of("PROFESSIONAL_REQUESTED", "PROFESSIONAL_VERIFIED", "PROFESSIONAL_EVIDENCE_ADDED", "PROFESSIONAL_APPROVED",
                "PROFESSIONAL_EVIDENCE_READ")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND entity_id = ? AND details->>'capacity' = 'NOTARY'",
                    Long.class, action, notary.id())).as(action).isPositive();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'PROFESSIONAL_REFUSED' AND reason = 'FOUR_EYES' AND entity_id = ?",
                Long.class, notary.id())).isEqualTo(1);
    }

    @Test
    void approvalWaitsForTwoKeysAndARejectionIsFinal() throws Exception {
        String email = email();
        Browser browser = new Browser();
        publicRequest(browser, email, "DEMO-N-" + UUID.randomUUID().toString().substring(0, 8));
        UUID id = userId(email);
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        String request = ok(verify(first, id, List.of("DEMOIT")), 201).path("id").asString("");
        assertThat(code(decide(second, request, true, null))).isEqualTo("SECURITY_KEYS_REQUIRED");
        assertThat(code(decide(second, request, false, "short"))).isEqualTo("REASON_REQUIRED");
        ok(decide(second, request, false, "The chamber does not know this notary"), 200);
        assertThat(code(verify(first, id, List.of("DEMOIT")))).as("rejected for good").isEqualTo("INVALID_STATUS");
    }

    @Test
    void oneAdministratorSuspendsAtOnceAndTwoAreNeededToReinstateRevokeOrMove() throws Exception {
        Notary notary = requestedNotary();
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        activated(first, second, notary);

        ok(sendJson(first.browser(), post(ADMIN + "/" + notary.id() + "/suspension"), body("reason", "Deposits reported as abusive")), 204);
        assertThat(me(notary).path("profile").path("status").asString("")).isEqualTo("SUSPENDED");

        String back = ok(change(first, notary.id(), "REINSTATEMENT", List.of(), List.of(), "The report was unfounded"), 201).path("id").asString("");
        assertThat(code(change(second, notary.id(), "REVOCATION", List.of(), List.of(), "A second open request"))).isEqualTo("REQUEST_ALREADY_OPEN");
        assertThat(code(decide(first, back, true, null))).isEqualTo("FOUR_EYES");
        ok(decide(second, back, true, null), 200);
        assertThat(me(notary).path("profile").path("status").asString("")).isEqualTo("ACTIVE");

        insertOffice("DEMOIT2");
        String move = ok(change(first, notary.id(), "OFFICE_CHANGE", List.of("DEMOIT2"), List.of("DEMOIT"), "Moved to the DEMOIT2 office"), 201)
                .path("id").asString("");
        assertThat(me(notary).path("profile").path("offices")).extracting(n -> n.asString("")).containsExactly("DEMOIT");
        ok(decide(second, move, true, null), 200);
        assertThat(me(notary).path("profile").path("offices")).extracting(n -> n.asString("")).containsExactly("DEMOIT2");
        assertThat(code(change(first, notary.id(), "OFFICE_CHANGE", List.of(), List.of("DEMOIT2"), "Removing the last office"))).isEqualTo("OFFICES_INVALID");

        String end = ok(change(second, notary.id(), "REVOCATION", List.of(), List.of(), "The chamber struck the notary off"), 201).path("id").asString("");
        assertThat(me(notary).path("profile").path("status").asString("")).as("not before the second administrator").isEqualTo("ACTIVE");
        ok(decide(first, end, true, null), 200);
        JsonNode revoked = me(notary).path("profile");
        assertThat(revoked.path("status").asString("")).isEqualTo("REVOKED");
        assertThat(revoked.path("offices")).isEmpty();
        assertThat(code(change(first, notary.id(), "REINSTATEMENT", List.of(), List.of(), "Bringing a revoked account back"))).isEqualTo("INVALID_STATUS");
        assertThat(me(notary).path("history").findValuesAsString("event")).containsSubsequence("REQUESTED", "VERIFIED", "ACTIVATION_APPROVED",
                "SUSPENDED", "REINSTATEMENT_APPROVED", "OFFICE_CHANGE_APPROVED", "REVOCATION_APPROVED");
    }

    @Test
    void aRequestPastItsTimeToLiveExpiresAndAnExpiredActivationNeedsANewVerification() throws Exception {
        Notary notary = requestedNotary();
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        String request = ok(verify(first, notary.id(), List.of("DEMOIT")), 201).path("id").asString("");
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE professional_request SET requested_at = now() - interval '8 days' WHERE id = '" + request + "'");
            su.commit();
        }
        MockHttpServletResponse late = decide(second, request, true, null);
        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("REQUEST_EXPIRED");
        assertThat(me(notary).path("profile").path("status").asString("")).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'PROFESSIONAL_REQUEST_EXPIRED' AND entity_id = ?", Long.class,
                notary.id())).isEqualTo(1);
        ok(verify(first, notary.id(), List.of("DEMOIT")), 201);
    }

    @Test
    void nobodyElseReachesTheLifecycleOrTheEvidence() throws Exception {
        Notary notary = requestedNotary();
        SignedIn first = signedIn("SYSTEM_ADMIN");
        ok(verify(first, notary.id(), List.of("DEMOIT")), 201);
        SignedIn intake = signedIn("INTAKE_OFFICER");
        SignedIn auditor = signedIn("AUDITOR");
        for (Browser outsider : List.of(notary.browser(), intake.browser(), auditor.browser())) {
            assertThat(outsider.send(get(ADMIN)).getStatus()).isEqualTo(403);
            assertThat(outsider.send(get(ADMIN + "/" + notary.id())).getStatus()).isEqualTo(403);
            assertThat(outsider.send(get(REQUESTS)).getStatus()).isEqualTo(403);
            outsider.csrf();
            assertThat(outsider.send(json(post(ADMIN + "/" + notary.id() + "/suspension"), body("reason", "Trying to suspend someone"))).getStatus())
                    .isEqualTo(403);
        }
        assertThat(intake.browser().send(get("/api/v1/professional/me")).getStatus()).as("not a professional").isEqualTo(404);
    }

    @Test
    void theRequestFormRevealsNoAccountAndChecksWhatItIsGiven() throws Exception {
        Browser browser = new Browser();
        String email = email();
        assertThat(publicRequest(browser, email, "DEMO-N-" + UUID.randomUUID().toString().substring(0, 8)).getStatus()).isEqualTo(201);
        assertThat(publicRequest(browser, email, "DEMO-N-" + UUID.randomUUID().toString().substring(0, 8)).getStatus())
                .as("the same answer for an address already registered").isEqualTo(201);
        assertThat(scalar("SELECT count(*) FROM professional_profile p JOIN app_user u ON u.id = p.user_id WHERE lower(u.email) = lower('" + email + "')"))
                .isEqualTo(1);

        String real = email();
        MockHttpServletResponse synthetic = publicRequest(browser, real, "N-12345");
        assertThat(synthetic.getStatus()).isEqualTo(422);
        assertThat(scalar("SELECT count(*) FROM app_user WHERE lower(email) = lower('" + real + "')"))
                .as("a refused profile leaves no account behind").isZero();
        browser.csrf();
        assertThat(code(browser.send(json(post("/api/v1/professional-requests"), body("email", email(), "displayName", "DEMO", "password", "short",
                "preferredLanguage", "fr", "profession", "NOTARY", "chamber", "DEMO C", "registrationNumber", "DEMO-1", "jurisdiction", "DEMO J")))))
                .isEqualTo("PASSWORD_WEAK");
        browser.csrf();
        assertThat(code(browser.send(json(post("/api/v1/professional-requests"), body("email", email(), "displayName", "DEMO", "password", PASSWORD,
                "preferredLanguage", "fr", "profession", "ARCHITECT", "chamber", "DEMO C", "registrationNumber", "DEMO-1", "jurisdiction", "DEMO J")))))
                .isEqualTo("PROFESSION_INVALID");
    }

    @Test
    void aProfessionalsLostKeysAreResetThroughASupervisorOfTheirOffice() throws Exception {
        Notary notary = requestedNotary();
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        activated(first, second, notary);
        SignedIn supervisor = signedIn("SUPERVISOR");

        MockHttpServletResponse asked = sendJson(first.browser(), post("/api/v1/admin/users/" + notary.id() + "/mfa-reset"),
                body("reason", "Both security keys lost at the office"));
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        String reset = json.readTree(asked.getContentAsString()).path("requestId").asString("");
        MockHttpServletResponse confirmed = sendJson(supervisor.browser(), post("/api/v1/identity/reset-confirmations/" + reset + "/confirm"),
                body("identityCheck", "In person, DEMO professional card checked"));
        assertThat(confirmed.getStatus()).as(confirmed.getContentAsString()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT mfa_enrolled_at IS NULL FROM app_user WHERE id = ?", Boolean.class, notary.id())).isTrue();
    }

    /** One number, read past row-level security, as a test may. */
    private long scalar(String sql) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertOffice(String code) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement()) {
            st.execute("INSERT INTO office (id, code, name_fr, name_en) VALUES (gen_random_uuid(), '" + code + "', 'Bureau', 'Office') ON CONFLICT (code) DO NOTHING");
            su.commit();
        }
    }
}
