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
        return publicRequest(browser, email, "DEMO Notary", "NOTARY", registration);
    }

    private MockHttpServletResponse publicRequest(Browser browser, String email, String name, String profession, String registration) throws Exception {
        browser.csrf();
        return browser.send(json(post("/api/v1/professional-requests"), body("email", email, "displayName", name, "password", PASSWORD,
                "preferredLanguage", "fr", "profession", profession, "chamber", "DEMO Chamber", "registrationNumber", registration,
                "jurisdiction", "DEMO Yaounde")));
    }

    /** An account requested on the public form, without keys: enough for the directory. */
    private UUID requested(String name, String profession, String registration) throws Exception {
        String email = email();
        assertThat(publicRequest(new Browser(), email, name, profession, registration).getStatus()).isEqualTo(201);
        return userId(email);
    }

    /**
     * The directory's answer for a query string, sent exactly as written (a URI, not a template that would encode '%'
     * again); the run's own accounts are found by a token in their number.
     */
    private JsonNode directory(SignedIn admin, String query) throws Exception {
        return ok(admin.browser().send(get(java.net.URI.create(ADMIN + "?" + query))), 200);
    }

    private static List<String> ids(JsonNode page) {
        return page.path("items").findValuesAsString("userId");
    }

    /** A professional who requested an account and enrolled two keys. */
    private Notary requestedNotary() throws Exception {
        return requestedNotaryNumbered("DEMO-N-" + UUID.randomUUID().toString().substring(0, 8));
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

    // --- The directory (D20) ----------------------------------------------------------------------------

    @Test
    void theDirectoryListsTheNewestAccountFirstAndBreaksATieByTheId() throws Exception {
        String token = "DEMO-ORD-" + UUID.randomUUID().toString().substring(0, 8);
        UUID oldest = requested("DEMO Oldest", "NOTARY", token + "-1");
        UUID tiedA = requested("DEMO Tied A", "NOTARY", token + "-2");
        UUID tiedB = requested("DEMO Tied B", "NOTARY", token + "-3");
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE professional_profile SET requested_at = now() - interval '2 days' WHERE user_id = '" + oldest + "'");
            st.execute("UPDATE professional_profile SET requested_at = now() - interval '1 day' WHERE user_id IN ('" + tiedA + "', '" + tiedB + "')");
            su.commit();
        }
        // Equal creation times: the larger id first, every time.
        List<String> tied = java.util.stream.Stream.of(tiedA, tiedB).sorted(java.util.Comparator.reverseOrder()).map(UUID::toString).toList();
        SignedIn admin = signedIn("SYSTEM_ADMIN");

        JsonNode all = directory(admin, "q=" + token);
        assertThat(ids(all)).containsExactly(tied.get(0), tied.get(1), oldest.toString());
        assertThat(all.path("total").asLong()).isEqualTo(3);
        assertThat(all.path("size").asInt()).as("the default").isEqualTo(50);
        assertThat(ids(directory(admin, "q=" + token))).as("deterministic").isEqualTo(ids(all));

        JsonNode first = directory(admin, "q=" + token + "&size=2&page=0");
        JsonNode second = directory(admin, "q=" + token + "&size=2&page=1");
        assertThat(ids(first)).containsExactly(tied.get(0), tied.get(1));
        assertThat(ids(second)).containsExactly(oldest.toString());
        assertThat(second.path("total").asLong()).as("the total on every page").isEqualTo(3);
        assertThat(ids(directory(admin, "q=" + token + "&size=2&page=2"))).as("past the end: empty, not an error").isEmpty();

        // The newest account of all comes first without any filter.
        UUID newest = requested("DEMO Newest", "NOTARY", token + "-4");
        assertThat(ids(directory(admin, "size=1"))).containsExactly(newest.toString());
    }

    @Test
    void theDirectoryFiltersByStatusProfessionOfficeAndWaitingStep() throws Exception {
        String token = "DEMO-FLT-" + UUID.randomUUID().toString().substring(0, 8);
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        UUID requestedOnly = requested("DEMO Requested", "NOTARY", token + "-R");
        UUID surveyor = requested("DEMO Surveyor", "SWORN_SURVEYOR", token + "-S");
        Notary verified = requestedNotaryNumbered(token + "-V");
        ok(verify(first, verified.id(), List.of("DEMOIT")), 201);
        insertOffice("DEMOIT3");
        Notary active = requestedNotaryNumbered(token + "-A");
        String activation = ok(verify(first, active.id(), List.of("DEMOIT3")), 201).path("id").asString("");
        ok(decide(second, activation, true, null), 200);
        ok(sendJson(first.browser(), post(ADMIN + "/" + active.id() + "/suspension"), body("reason", "Deposits reported as abusive")), 204);
        ok(change(first, active.id(), "REINSTATEMENT", List.of(), List.of(), "The report was unfounded"), 201);

        String q = "q=" + token;
        assertThat(ids(directory(first, q + "&status=REQUESTED"))).containsExactlyInAnyOrder(requestedOnly.toString(), surveyor.toString());
        assertThat(ids(directory(first, q + "&status=SUSPENDED"))).containsExactly(active.id().toString());
        assertThat(ids(directory(first, q + "&profession=SWORN_SURVEYOR"))).containsExactly(surveyor.toString());
        assertThat(ids(directory(first, q + "&profession=NOTARY&status=REQUESTED"))).as("filters combine with AND")
                .containsExactly(requestedOnly.toString());
        assertThat(ids(directory(first, q + "&office=DEMOIT"))).as("an office proposed by the verification").containsExactly(verified.id().toString());
        assertThat(ids(directory(first, q + "&office=DEMOIT3"))).as("an approved office").containsExactly(active.id().toString());
        assertThat(ids(directory(first, q + "&office=NOWHERE"))).isEmpty();
        assertThat(ids(directory(first, q + "&pending=VERIFICATION"))).containsExactlyInAnyOrder(requestedOnly.toString(), surveyor.toString());
        assertThat(ids(directory(first, q + "&pending=ACTIVATION"))).containsExactly(verified.id().toString());
        assertThat(ids(directory(first, q + "&pending=CHANGE"))).containsExactly(active.id().toString());
        assertThat(directory(first, q + "&pending=CHANGE").path("total").asLong()).isEqualTo(1);

        // A request past its time to live is no longer waiting, though the queue has not closed it yet.
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE professional_request SET requested_at = now() - interval '8 days' WHERE user_id = '" + verified.id()
                    + "' AND status = 'PENDING'");
            su.commit();
        }
        assertThat(ids(directory(first, q + "&pending=ACTIVATION"))).isEmpty();
        assertThat(ids(directory(first, q + "&office=DEMOIT"))).as("a proposal that expired").isEmpty();
    }

    @Test
    void theDirectorySearchIgnoresCaseAndAccentsAndTakesWildcardsLiterally() throws Exception {
        String token = "DEMO-SRCH-" + UUID.randomUUID().toString().substring(0, 8);
        UUID helene = requested("DEMO Hélène Ngô Œuvray", "NOTARY", token + "-1");
        UUID other = requested("DEMO Paul Mbarga", "NOTARY", token + "-2");
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        String q = "&q=";

        assertThat(ids(directory(admin, "q=" + token))).containsExactlyInAnyOrder(helene.toString(), other.toString());
        assertThat(ids(directory(admin, "q=" + token.toLowerCase()))).as("the registration number, any case").hasSize(2);
        for (String term : List.of("HELENE NGO", "hélène", "Ngo Oeuvray", "OEUVRAY")) {
            // Encoded as the screen does (encodeURIComponent): a space is %20.
            String encoded = java.net.URLEncoder.encode(term, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            assertThat(ids(directory(admin, "status=REQUESTED" + q + encoded)))
                    .as(term).contains(helene.toString()).doesNotContain(other.toString());
        }
        String email = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, helene);
        assertThat(ids(directory(admin, "q=" + email.toUpperCase()))).as("the e-mail").containsExactly(helene.toString());
        assertThat(directory(admin, "q=demo%20CHAMBER&size=1").path("total").asLong()).as("the chamber").isGreaterThanOrEqualTo(2);

        // '%' and '_' are ordinary characters: as LIKE patterns, both would match the two accounts.
        assertThat(ids(directory(admin, "q=" + token + "%25"))).isEmpty();
        assertThat(ids(directory(admin, "q=" + token.substring(0, token.length() - 1) + "_"))).isEmpty();
        assertThat(ids(directory(admin, "q=" + token.substring(0, 5) + "_" + token.substring(6)))).isEmpty();
        assertThat(ids(directory(admin, "q=%20%20" + token + "%20"))).as("the term is trimmed").hasSize(2);
    }

    @Test
    void theDirectoryRefusesWhatIsOutOfBoundsAndStaysForAdministrators() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("size=0", "SIZE_OUT_OF_RANGE");
        refused.put("size=101", "SIZE_OUT_OF_RANGE");
        refused.put("page=-1", "PAGE_OUT_OF_RANGE");
        refused.put("page=10001", "PAGE_OUT_OF_RANGE");
        refused.put("q=a", "SEARCH_INVALID");
        refused.put("q=" + "a".repeat(101), "SEARCH_INVALID");
        refused.put("status=GONE", "STATUS_INVALID");
        refused.put("profession=ARCHITECT", "PROFESSION_INVALID");
        refused.put("pending=SOON", "PENDING_INVALID");
        refused.put("office=YDE1%27%20OR%201%3D1", "OFFICE_INVALID");
        for (Map.Entry<String, String> e : refused.entrySet()) {
            MockHttpServletResponse answer = admin.browser().send(get(java.net.URI.create(ADMIN + "?" + e.getKey())));
            assertThat(answer.getStatus()).as(e.getKey()).isEqualTo(422);
            assertThat(code(answer)).as(e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(directory(admin, "size=100&q=" + "a".repeat(100)).path("size").asInt()).as("the bounds themselves").isEqualTo(100);
        assertThat(directory(admin, "q=%20").path("size").asInt()).as("a blank term is no search").isEqualTo(50);

        Notary notary = requestedNotary();
        for (Browser outsider : List.of(notary.browser(), signedIn("AUDITOR").browser(), signedIn("INTAKE_OFFICER").browser())) {
            assertThat(outsider.send(get(java.net.URI.create(ADMIN + "?q=DEMO&status=ACTIVE&pending=CHANGE"))).getStatus()).isEqualTo(403);
        }
    }

    // --- The dashboard's counts (D21) ----------------------------------------------------------------------

    @Test
    void theDashboardCountsOnlyWhatEachAdministratorMayActOnAndNeverTheirOwn() throws Exception {
        customRole("DEMO_PRO_READER", "admin.professional.read");
        customRole("DEMO_PRO_VERIFIER", "admin.professional.read", "admin.professional.verify");
        customRole("DEMO_PRO_APPROVER", "admin.professional.read", "admin.professional.approve");
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        SignedIn verifier = signedIn("DEMO_PRO_VERIFIER");
        SignedIn approver = signedIn("DEMO_PRO_APPROVER");
        SignedIn reader = signedIn("DEMO_PRO_READER");

        // Only what each may act on: a count they may not act on is left out, never sent as zero.
        assertThat(fields(counts(first))).containsExactly("requests", "activations", "changes", "waitingForOthers");
        assertThat(fields(counts(verifier))).containsExactly("requests", "waitingForOthers");
        assertThat(fields(counts(approver))).containsExactly("activations", "changes");
        assertThat(fields(counts(reader))).as("a reader only gets no count").isEmpty();

        JsonNode first0 = counts(first);
        JsonNode second0 = counts(second);
        JsonNode verifier0 = counts(verifier);
        JsonNode approver0 = counts(approver);

        // An account requested on the public form waits for a verification.
        requested("DEMO Counted", "NOTARY", "DEMO-CNT-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(n(counts(verifier), "requests")).isEqualTo(n(verifier0, "requests") + 1);
        assertThat(n(counts(second), "requests")).isEqualTo(n(second0, "requests") + 1);

        // A verification waits for another administrator, never for the one who recorded it.
        Notary notary = requestedNotary();
        String activation = ok(verify(first, notary.id(), List.of("DEMOIT")), 201).path("id").asString("");
        JsonNode mine = counts(first);
        assertThat(n(mine, "requests")).as("the verified account no longer waits for a verification").isEqualTo(n(first0, "requests") + 1);
        assertThat(n(mine, "activations")).as("not one's own verification").isEqualTo(n(first0, "activations"));
        assertThat(n(mine, "waitingForOthers")).isEqualTo(n(first0, "waitingForOthers") + 1);
        assertThat(n(counts(second), "activations")).isEqualTo(n(second0, "activations") + 1);
        assertThat(n(counts(approver), "activations")).isEqualTo(n(approver0, "activations") + 1);

        // A decided request is never counted again.
        ok(decide(second, activation, true, null), 200);
        assertThat(n(counts(approver), "activations")).isEqualTo(n(approver0, "activations"));
        assertThat(n(counts(first), "waitingForOthers")).isEqualTo(n(first0, "waitingForOthers"));

        // A reinstatement, office change or revocation waits for an administrator who did not ask for it.
        ok(sendJson(first.browser(), post(ADMIN + "/" + notary.id() + "/suspension"), body("reason", "Deposits reported as abusive")), 204);
        String back = ok(change(first, notary.id(), "REINSTATEMENT", List.of(), List.of(), "The report was unfounded"), 201).path("id").asString("");
        mine = counts(first);
        assertThat(n(mine, "changes")).as("not one's own request").isEqualTo(n(first0, "changes"));
        assertThat(n(mine, "waitingForOthers")).isEqualTo(n(first0, "waitingForOthers") + 1);
        assertThat(n(counts(second), "changes")).isEqualTo(n(second0, "changes") + 1);
        assertThat(n(counts(approver), "changes")).isEqualTo(n(approver0, "changes") + 1);
        ok(decide(second, back, false, "The report was confirmed by the chamber"), 200);
        assertThat(n(counts(approver), "changes")).as("a rejected request").isEqualTo(n(approver0, "changes"));

        // A request past its time to live is no longer pending, though nothing has closed it yet.
        ok(change(first, notary.id(), "REINSTATEMENT", List.of(), List.of(), "New evidence from the chamber"), 201);
        assertThat(n(counts(approver), "changes")).isEqualTo(n(approver0, "changes") + 1);
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE professional_request SET requested_at = now() - interval '8 days' WHERE user_id = '" + notary.id()
                    + "' AND status = 'PENDING'");
            su.commit();
        }
        assertThat(n(counts(approver), "changes")).isEqualTo(n(approver0, "changes"));
        assertThat(n(counts(first), "waitingForOthers")).isEqualTo(n(first0, "waitingForOthers"));

        for (Browser outsider : List.of(notary.browser(), signedIn("AUDITOR").browser(), signedIn("INTAKE_OFFICER").browser())) {
            assertThat(outsider.send(get(ADMIN + "/counts")).getStatus()).isEqualTo(403);
        }
    }

    private JsonNode counts(SignedIn admin) throws Exception {
        return ok(admin.browser().send(get(ADMIN + "/counts")), 200);
    }

    private static long n(JsonNode counts, String name) {
        assertThat(counts.has(name)).as(name).isTrue();
        return counts.path(name).asLong();
    }

    private static List<String> fields(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    /** A custom role with exactly these permissions, everywhere; written past the role workflow's guards, as a test may. */
    private void customRole(String code, String... permissions) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("INSERT INTO role_definition (code, name_fr, name_en) VALUES ('" + code + "', 'Rôle de test', 'Test role') ON CONFLICT (code) DO NOTHING");
            for (String permission : permissions) {
                st.execute("INSERT INTO role_permission (role_code, permission, scope) VALUES ('" + code + "', '" + permission + "', 'ANY')"
                        + " ON CONFLICT DO NOTHING");
            }
            su.commit();
        }
    }

    /** A requested notary with two keys, whose registration number carries the test's token. */
    private Notary requestedNotaryNumbered(String registration) throws Exception {
        String email = email();
        Browser browser = new Browser();
        assertThat(publicRequest(browser, email, registration).getStatus()).isEqualTo(201);
        browser.login(email, PASSWORD);
        register(browser, new Authenticator(), "Office key");
        register(browser, new Authenticator(), "Backup key");
        browser.csrf();
        return new Notary(userId(email), email, browser);
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
