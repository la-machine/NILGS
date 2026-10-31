// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.document.DocumentFixtures;
import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.application.DocumentService;
import org.snigf.mvp.register.application.OverdueLocks;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The DEMO land register through the real endpoints (ADR-040, Slice 2 acceptance): every operation
 * and refusal, both dual-control refusals, the signature failures, tampering behind the signatures,
 * counter deposits with the receiving officer and the notary kept distinct, overdue locks that never
 * expire. The authenticators are webauthn4j emulators, so the signatures are genuine.
 */
class RegisterIT extends CaseFlowIntegrationTest {

    private static final String BASE = "/api/v1/register";
    private static final String OFFICE = "DEMOIT";

    @Autowired
    private OverdueLocks overdueLocks;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentProperties documentProperties;

    /** A conservator with the first of their two keys in hand. */
    private record Officer(UUID id, Browser browser, Authenticator key) {
    }

    private Officer conservator() throws Exception {
        return conservator(OFFICE);
    }

    private Officer conservator(String office) throws Exception {
        String email = email();
        UUID id = insertStaff(email, "CONSERVATOR", office);
        Browser browser = new Browser();
        browser.login(email, PASSWORD);
        Authenticator key = new Authenticator();
        register(browser, key, "Office key");
        register(browser, new Authenticator(), "Backup key");
        browser.csrf();
        return new Officer(id, browser, key);
    }

    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    private MockHttpServletResponse postJson(Officer who, String path, Object... pairs) throws Exception {
        return sendJson(who.browser(), post(BASE + path), body(pairs));
    }

    private MockHttpServletResponse created(Officer who, String path, String key, String bodyJson) throws Exception {
        who.browser().csrf();
        return who.browser().send(json(post(BASE + path).header("Idempotency-Key", key), bodyJson));
    }

    private String party(Officer who, String name) throws Exception {
        return ok(postJson(who, "/parties", "officeCode", OFFICE, "kind", "NATURAL_PERSON", "fullName", name, "identityKind", "NATIONAL_ID",
                "identityNumber", "DEMO-" + UUID.randomUUID()), 201).path("id").asString("");
    }

    private String notary(Officer who) throws Exception {
        return ok(postJson(who, "/parties", "officeCode", OFFICE, "kind", "NOTARY", "fullName", "DEMO Notary", "identityKind", "NATIONAL_ID",
                "identityNumber", "DEMO-" + UUID.randomUUID(), "chamber", "DEMO Chamber", "registrationNumber", "DEMO-N-1",
                "jurisdiction", "DEMO Jurisdiction"), 201).path("id").asString("");
    }

    private String parcelVersion() throws Exception {
        String reference = parcel(signedIn("CADASTRAL_OFFICER"), area(), 0);
        return jdbc.queryForObject("""
                SELECT v.id::text FROM parcel_version v JOIN parcel p ON p.id = v.parcel_id
                 WHERE p.reference = ? AND v.version_no = 1""", String.class, reference);
    }

    private static List<Map<String, Object>> holder(String party) {
        return List.of(Map.of("party", party, "interest", "OWNERSHIP", "shareNum", 1, "shareDen", 1));
    }

    private String proposal(String title, String kind, int expected, Object... extra) throws Exception {
        java.util.List<Object> pairs = new java.util.ArrayList<>(List.of("officeCode", OFFICE, "kind", kind, "expectedVersion", expected,
                "authorityType", "SYNTHETIC_DEMO", "authorityReference", "DEMO-AUTH-" + kind, "reason", "A synthetic reason for " + kind,
                "evidenceReferences", List.of("DEMO-EVIDENCE-1")));
        if (title != null) {
            pairs.add("titleId");
            pairs.add(title);
        }
        pairs.addAll(List.of(extra));
        return body(pairs.toArray());
    }

    private String propose(Officer who, String title, String kind, int expected, Object... extra) throws Exception {
        return ok(created(who, "/inscriptions", key(), proposal(title, kind, expected, extra)), 201).path("id").asString("");
    }

    /** Runs one signing act with the officer's own key. */
    private MockHttpServletResponse sign(Officer who, Authenticator key, String id, String act) throws Exception {
        who.browser().csrf();
        MockHttpServletResponse started = who.browser().send(post(BASE + "/inscriptions/" + id + "/signature/options"));
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(200);
        who.browser().csrf();
        return who.browser().send(json(post(BASE + "/inscriptions/" + id + "/" + act), key.get(started.getContentAsString())));
    }

    private JsonNode signed(Officer who, String id, String act) throws Exception {
        return ok(sign(who, who.key(), id, act), 200);
    }

    /** A registered title with one holder; returns its id. */
    private String title(Officer who, String holderParty) throws Exception {
        String id = propose(who, null, "CREATION", 0, "areaM2", 10000, "parcelVersionId", parcelVersion(), "holders", holder(holderParty));
        return signed(who, id, "register").path("titleId").asString("");
    }

    private String deposit(Officer who, String title, String depositor, String notary) throws Exception {
        java.util.List<Object> pairs = new java.util.ArrayList<>(List.of("titleId", title, "kind", "SALE", "depositorPartyId", depositor,
                "evidenceReferences", List.of("DEMO-DEED-1")));
        if (notary != null) {
            pairs.add("submittedByPartyId");
            pairs.add(notary);
        }
        return ok(created(who, "/transactions", key(), body(pairs.toArray())), 201).path("id").asString("");
    }

    private JsonNode titleView(Officer who, String title) throws Exception {
        return ok(who.browser().send(get(BASE + "/titles/" + title)), 200);
    }

    private JsonNode verification(Officer who, String title) throws Exception {
        return ok(who.browser().send(get(BASE + "/titles/" + title + "/verification")), 200);
    }

    // --- Journeys -----------------------------------------------------------------------------------

    @Test
    void aConservatorRegistersATransferThroughTheLockAndASecondConservatorResolvesTheConflict() throws Exception {
        Officer first = conservator();
        Officer second = conservator();
        String seller = party(first, "DEMO Seller");
        String buyer = party(first, "DEMO Buyer");
        String rival = party(first, "DEMO Rival");

        // Proposed is not registered: no title yet, and the proposal waits in the queue.
        String creation = propose(first, null, "CREATION", 0, "areaM2", 10000, "parcelVersionId", parcelVersion(), "holders", holder(seller));
        JsonNode queue = ok(first.browser().send(get(BASE + "/proposals").param("office", OFFICE)), 200);
        assertThat(queue.findValuesAsString("id")).contains(creation);
        assertThat(scalar("SELECT count(*) FROM land_title t JOIN inscription i ON i.new_title_id = t.id WHERE i.id = '" + creation + "'")).isZero();

        JsonNode registered = signed(first, creation, "register");
        assertThat(registered.path("status").asString("")).isEqualTo("REGISTERED");
        assertThat(registered.path("number").asInt()).isEqualTo(1);
        String title = registered.path("titleId").asString("");
        assertThat(registered.path("titleNumber").asString("")).matches("DEMO-TF-DEMOIT-[0-9]{6}-[A-Z]");

        JsonNode view = titleView(first, title);
        assertThat(view.path("title").path("holders").get(0).path("partyName").asString("")).isEqualTo("DEMO Seller");
        assertThat(view.path("title").path("status").asString("")).isEqualTo("ACTIVE");

        // The extract says what it is, and offers no certificate.
        JsonNode extract = ok(first.browser().send(get(BASE + "/titles/" + title + "/extract")), 200);
        assertThat(extract.path("label").asString("")).isEqualTo("NOT A CERTIFICATE — DEMONSTRATION DATA");
        assertThat(extract.has("qrCode") || extract.has("reference") || extract.has("signature")).isFalse();

        // A deposit holds the title; a second is recorded in conflict, never refused.
        String holding = deposit(first, title, buyer, null);
        String arriving = deposit(first, title, rival, null);
        view = titleView(first, title);
        assertThat(view.path("transactions").findValuesAsString("status")).containsExactly("HOLDING", "IN_CONFLICT");
        String conflict = view.path("conflicts").get(0).path("id").asString("");

        // No transfer through the open conflict.
        String transfer = propose(first, title, "TRANSFER", 1, "completesTransactionId", holding, "holders", holder(buyer));
        MockHttpServletResponse blocked = sign(first, first.key(), transfer, "register");
        assertThat(blocked.getStatus()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("OPEN_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_REFUSED' AND reason = 'OPEN_CONFLICT' AND entity_id = ?::uuid",
                Long.class, transfer)).isEqualTo(1);

        // Who received a deposit does not decide; a second conservator does.
        MockHttpServletResponse self = postJson(first, "/conflicts/" + conflict + "/resolution", "outcome", "KEEP_HOLDING", "reason", "The first deposit stands",
                "evidenceReferences", List.of("DEMO-DECISION-1"));
        assertThat(self.getStatus()).isEqualTo(403);
        assertThat(code(self)).isEqualTo("RECEIVER_CANNOT_DECIDE");
        ok(postJson(second, "/conflicts/" + conflict + "/resolution", "outcome", "KEEP_HOLDING", "reason", "The first deposit stands",
                "evidenceReferences", List.of("DEMO-DECISION-1")), 200);
        assertThat(titleView(first, title).path("transactions").findValuesAsString("status")).containsExactly("HOLDING", "REFUSED");

        // Registered through the lock: the holder changes, the previous one stays in the history.
        JsonNode done = signed(first, transfer, "register");
        assertThat(done.path("number").asInt()).isEqualTo(2);
        view = titleView(first, title);
        assertThat(view.path("title").path("holders").get(0).path("partyName").asString("")).isEqualTo("DEMO Buyer");
        assertThat(view.path("title").path("version").asInt()).isEqualTo(2);
        assertThat(view.path("inscriptions").findValuesAsString("kind")).containsExactly("CREATION", "TRANSFER");
        assertThat(view.path("inscriptions").get(0).path("holders").get(0).path("partyName").asString("")).isEqualTo("DEMO Seller");
        assertThat(view.path("transactions").get(0).path("status").asString("")).isEqualTo("REGISTERED");

        // Row against signed bytes (Java and SQL encodings agree), both signatures, chain, derived state.
        JsonNode check = verification(first, title);
        assertThat(check.path("intact").asBoolean()).as(check.toString()).isTrue();
        assertThat(check.path("inscriptionsChecked").asInt()).isEqualTo(2);
        assertThat(check.path("signaturesChecked").asInt()).isEqualTo(2);
        assertThat(check.path("versionsChecked").asInt()).isEqualTo(2);

        for (String action : List.of("REGISTER_INSCRIPTION_PROPOSED", "REGISTER_INSCRIPTION_REGISTERED", "REGISTER_TRANSACTION_DEPOSITED",
                "REGISTER_CONFLICT_OPENED", "REGISTER_CONFLICT_RESOLVED", "REGISTER_REFUSED")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND subjects @> ?::jsonb",
                    Long.class, action, "[{\"type\":\"LAND_TITLE\",\"id\":\"" + title + "\"}]")).as(action).isPositive();
        }
        assertThat(arriving).isNotBlank();
    }

    @Test
    void aCorrectionNeedsTwoConservatorsAndBothSign() throws Exception {
        Officer first = conservator();
        Officer second = conservator();
        String misspelt = party(first, "DEMO Misspelt");
        String right = party(first, "DEMO Right");
        String title = title(first, misspelt);
        String creation = titleView(first, title).path("inscriptions").get(0).path("id").asString("");
        String correction = propose(first, title, "CORRECTION", 1, "targetInscriptionId", creation, "holders", holder(right));

        MockHttpServletResponse alone = sign(first, first.key(), correction, "register");
        assertThat(alone.getStatus()).isEqualTo(409);
        assertThat(code(alone)).isEqualTo("DUAL_CONTROL_REQUIRED");

        MockHttpServletResponse early = sign(second, second.key(), correction, "confirm");
        assertThat(code(early)).as("not yet signed by its proposer").isEqualTo("NOT_PENDING_CONFIRMATION");
        MockHttpServletResponse otherAsks = sign(second, second.key(), correction, "request-confirmation");
        assertThat(code(otherAsks)).isEqualTo("NOT_THE_PROPOSER");

        assertThat(signed(first, correction, "request-confirmation").path("status").asString("")).isEqualTo("CONFIRMATION_PENDING");
        assertThat(titleView(first, title).path("title").path("holders").get(0).path("partyName").asString(""))
                .as("not registered yet: no effect").isEqualTo("DEMO Misspelt");

        MockHttpServletResponse own = sign(first, first.key(), correction, "confirm");
        assertThat(own.getStatus()).as("nobody confirms their own proposal").isEqualTo(403);
        assertThat(code(own)).isEqualTo("SELF_CONFIRMATION");

        assertThat(signed(second, correction, "confirm").path("status").asString("")).isEqualTo("REGISTERED");
        JsonNode view = titleView(first, title);
        assertThat(view.path("title").path("holders").get(0).path("partyName").asString("")).isEqualTo("DEMO Right");
        JsonNode original = view.path("inscriptions").get(0);
        assertThat(original.path("holders").get(0).path("partyName").asString("")).as("the original is kept").isEqualTo("DEMO Misspelt");
        assertThat(original.path("correctedByNumber").asInt()).isEqualTo(2);
        JsonNode check = verification(second, title);
        assertThat(check.path("intact").asBoolean()).as(check.toString()).isTrue();
        assertThat(check.path("signaturesChecked").asInt()).as("the proposer's and the confirmer's").isEqualTo(3);
    }

    @Test
    void chargesAndBlockingRestrictionsAndTheirRelease() throws Exception {
        Officer officer = conservator();
        Officer second = conservator();
        String holderParty = party(officer, "DEMO Owner");
        String bank = party(officer, "DEMO Bank");
        String title = title(officer, holderParty);
        String mortgage = propose(officer, title, "CHARGE", 1, "charge", Map.of("kind", "MORTGAGE", "beneficiary", bank, "amount", 1000000, "blocksTransfer", false, "note", "DEMO mortgage"));
        signed(officer, mortgage, "register");
        assertThat(titleView(officer, title).path("title").path("charges").get(0).path("beneficiaryName").asString("")).isEqualTo("DEMO Bank");

        String opposition = propose(officer, title, "RESTRICTION", 2, "charge", Map.of("kind", "OPPOSITION", "blocksTransfer", true, "note", "DEMO opposition"));
        signed(officer, opposition, "register");
        String holding = deposit(officer, title, bank, null);
        String transfer = propose(officer, title, "TRANSFER", 3, "completesTransactionId", holding, "holders", holder(bank));
        assertThat(code(sign(officer, officer.key(), transfer, "register"))).isEqualTo("BLOCKING_RESTRICTION");

        String lift = propose(second, title, "RESTRICTION_RELEASE", 3, "targetInscriptionId", opposition);
        signed(second, lift, "register");
        assertThat(code(sign(officer, officer.key(), transfer, "register"))).as("prepared before the lift").isEqualTo("TITLE_CHANGED");

        String withdrawnReason = "The proposal was prepared against an earlier version";
        ok(postJson(officer, "/inscriptions/" + transfer + "/withdraw", "reason", withdrawnReason), 200);
        assertThat(titleView(officer, title).path("inscriptions").findValuesAsString("status")).contains("WITHDRAWN");
        assertThat(code(postJson(second, "/inscriptions/" + mortgage + "/withdraw", "reason", withdrawnReason))).isEqualTo("INSCRIPTION_CLOSED");
        JsonNode check = verification(officer, title);
        assertThat(check.path("intact").asBoolean()).as(check.toString()).isTrue();
    }

    @Test
    void aStaleProposalIsRefused() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String title = title(officer, owner);
        String stale = propose(officer, title, "CHARGE", 1, "charge", Map.of("kind", "SERVITUDE", "blocksTransfer", false, "note", "DEMO servitude"));
        String newer = propose(officer, title, "CHARGE", 1, "charge", Map.of("kind", "PRIVILEGE", "blocksTransfer", false, "note", "DEMO privilege"));
        signed(officer, newer, "register");

        MockHttpServletResponse refused = sign(officer, officer.key(), stale, "register");
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("TITLE_CHANGED");
        assertThat(code(created(officer, "/inscriptions", key(), proposal(title, "CHARGE", 1, "charge", Map.of("kind", "SERVITUDE", "blocksTransfer", false)))))
                .isEqualTo("TITLE_CHANGED");
    }

    // --- Signature failures, through the API ------------------------------------------------------------

    @Test
    void signaturesThatDoNotHoldRegisterNothing() throws Exception {
        Officer officer = conservator();
        Officer other = conservator();
        String owner = party(officer, "DEMO Owner");
        String version = parcelVersion();
        String id = propose(officer, null, "CREATION", 0, "areaM2", 10000, "parcelVersionId", version, "holders", holder(owner));
        String another = propose(officer, null, "CREATION", 0, "areaM2", 20000, "parcelVersionId", version, "holders", holder(owner));

        // Another officer's key answering this officer's ceremony.
        officer.browser().csrf();
        String options = officer.browser().send(post(BASE + "/inscriptions/" + id + "/signature/options")).getContentAsString();
        officer.browser().csrf();
        MockHttpServletResponse wrongKey = officer.browser().send(json(post(BASE + "/inscriptions/" + id + "/register"),
                other.key().get(options, other.key().credentialId)));
        assertThat(wrongKey.getStatus()).isEqualTo(422);
        assertThat(code(wrongKey)).isEqualTo("SIGNATURE_INVALID");

        // A signature begun over one inscription, presented for another: the bytes differ.
        officer.browser().csrf();
        options = officer.browser().send(post(BASE + "/inscriptions/" + another + "/signature/options")).getContentAsString();
        officer.browser().csrf();
        MockHttpServletResponse otherBytes = officer.browser().send(json(post(BASE + "/inscriptions/" + id + "/register"), officer.key().get(options)));
        assertThat(code(otherBytes)).isEqualTo("SIGNATURE_INVALID");

        // The same response again: the ceremony is gone.
        officer.browser().csrf();
        options = officer.browser().send(post(BASE + "/inscriptions/" + id + "/signature/options")).getContentAsString();
        String response = officer.key().get(options);
        officer.browser().csrf();
        ok(officer.browser().send(json(post(BASE + "/inscriptions/" + id + "/register"), response)), 200);
        officer.browser().csrf();
        MockHttpServletResponse replayed = officer.browser().send(json(post(BASE + "/inscriptions/" + another + "/register"), response));
        assertThat(code(replayed)).isEqualTo("SIGNATURE_NOT_STARTED");
        assertThat(ok(officer.browser().send(get(BASE + "/inscriptions/" + another)), 200).path("status").asString("")).isEqualTo("PROPOSED");

        assertThat(scalar("SELECT count(*) FROM inscription_signature WHERE inscription_id = '" + another + "'")).as("nothing stored").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_REFUSED' AND reason = 'SIGNATURE_INVALID' AND entity_id = ?::uuid",
                Long.class, id)).isPositive();
    }

    @Test
    void changingARowBehindTheSignaturesIsDetected() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String title = title(officer, owner);
        assertThat(verification(officer, title).path("intact").asBoolean()).isTrue();

        // What a database administrator could do by turning the guard off.
        try (Connection super1 = PostgisDatabase.shared().superuserConnection(); Statement st = super1.createStatement()) {
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE inscription SET reason = 'A reason nobody signed at all' WHERE title_id = '" + title + "'");
            super1.commit();
        }
        JsonNode check = verification(officer, title);
        assertThat(check.path("intact").asBoolean()).isFalse();
        assertThat(check.path("problems").toString()).contains("differs from what was signed");
    }

    // --- Counter deposits and locks ---------------------------------------------------------------------

    @Test
    void aCounterDepositKeepsTheReceivingOfficerAndTheNotaryDistinct() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String buyer = party(officer, "DEMO Buyer");
        String title = title(officer, owner);
        String notary = notary(officer);

        String id = deposit(officer, title, buyer, notary);

        JsonNode t = titleView(officer, title).path("transactions").get(0);
        assertThat(t.path("id").asString("")).isEqualTo(id);
        assertThat(t.path("channel").asString("")).isEqualTo("COUNTER");
        assertThat(t.path("submittedByName").asString("")).isEqualTo("DEMO Notary");
        assertThat(t.path("receivedBy").asString("")).isEqualTo(officer.id().toString());
        assertThat(jdbc.queryForObject("SELECT details->>'channel' FROM audit_event WHERE action = 'REGISTER_TRANSACTION_DEPOSITED' AND entity_id = ?::uuid",
                String.class, id)).isEqualTo("COUNTER");
        assertThat(code(created(officer, "/transactions", key(), body("titleId", title, "kind", "SALE", "depositorPartyId", notary,
                "evidenceReferences", List.of("DEMO-DEED"))))).as("a notary submits, and deposits nothing of their own").isEqualTo("DEPOSITOR_INVALID");
    }

    @Test
    void aLockPastItsReviewDateIsRecordedOnceAndNeverExpires() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String title = title(officer, owner);
        String fresh = deposit(officer, title(officer, owner), owner, null);
        assertThat(ok(officer.browser().send(get(BASE + "/transactions").param("office", OFFICE).param("overdue", "true")), 200).findValuesAsString("id"))
                .doesNotContain(fresh);

        // The review period is a setting; a very short one here, as the function takes it.
        String id;
        try (Connection super1 = PostgisDatabase.shared().superuserConnection(); Statement st = super1.createStatement()) {
            st.execute("SELECT set_config('snigf.actor_id', '" + officer.id() + "', false)");
            try (var rs = st.executeQuery("SELECT transaction_deposit('" + title + "', 'SALE', '" + owner + "', NULL, ARRAY['DEMO-DEED'], interval '1 millisecond')")) {
                rs.next();
                id = rs.getString(1);
            }
            st.execute("SELECT pg_sleep(0.01)");
            super1.commit();
        }
        assertThat(overdueLocks.record()).isPositive();
        assertThat(overdueLocks.record()).as("recorded once").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_LOCK_OVERDUE' AND entity_id = ?::uuid", Long.class, id)).isEqualTo(1);
        assertThat(ok(officer.browser().send(get(BASE + "/transactions").param("office", OFFICE).param("overdue", "true")), 200).findValuesAsString("id"))
                .contains(id);
        assertThat(titleView(officer, title).path("transactions").get(0).path("status").asString("")).as("still holding: only a decision releases it").isEqualTo("HOLDING");

        ok(postJson(officer, "/transactions/" + id + "/refuse", "reason", "Reviewed: the deposit was never completed"), 200);
        assertThat(titleView(officer, title).path("transactions").get(0).path("status").asString("")).isEqualTo("REFUSED");
    }

    // --- A deposit's documents (ADR-040 §4.7) -------------------------------------------------------------

    private MockHttpServletResponse upload(Officer who, String transaction, String category, String name, byte[] bytes, String key) throws Exception {
        who.browser().csrf();
        return who.browser().send(multipart(BASE + "/transactions/" + transaction + "/documents").file(new MockMultipartFile("file", name, "application/pdf", bytes))
                .param("category", category).header("Idempotency-Key", key));
    }

    private MockHttpServletResponse replace(Officer who, String transaction, String document, String name, byte[] bytes, String reason) throws Exception {
        who.browser().csrf();
        return who.browser().send(multipart(BASE + "/transactions/" + transaction + "/documents/" + document + "/replacement")
                .file(new MockMultipartFile("file", name, "application/pdf", bytes)).param("category", "DEED").param("reason", reason)
                .header("Idempotency-Key", key()));
    }

    private JsonNode documents(Officer who, String transaction) throws Exception {
        return ok(who.browser().send(get(BASE + "/transactions/" + transaction + "/documents")), 200);
    }

    @Test
    void aDepositKeepsItsDocumentsWithoutAnyApplicationAndStoringOneRegistersNothing() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String title = title(officer, owner);
        JsonNode before = titleView(officer, title);
        String holding = deposit(officer, title, owner, null);

        byte[] deed = DocumentFixtures.pdf("/Title (DEMO deed)");
        String key = key();
        String id = ok(upload(officer, holding, "DEED", "deed.pdf", deed, key), 201).path("id").asString("");
        assertThat(ok(upload(officer, holding, "DEED", "deed.pdf", deed, key), 201).path("id").asString("")).as("a retry adds nothing").isEqualTo(id);

        JsonNode listed = documents(officer, holding);
        assertThat(listed).hasSize(1);
        JsonNode one = listed.get(0);
        assertThat(one.path("originalName").asString("")).isEqualTo("deed.pdf");
        assertThat(one.path("mediaType").asString("")).isEqualTo("application/pdf");
        assertThat(one.path("sizeBytes").asLong()).isEqualTo(deed.length);
        assertThat(one.path("sha256").asString("")).hasSize(64);
        assertThat(one.path("uploadedBy").asString("")).isEqualTo(officer.id().toString());
        assertThat(one.path("uploadedAt").asString("")).isNotBlank();
        assertThat(one.has("storageKey")).as("the storage key is never shown").isFalse();
        assertThat(scalar("SELECT count(*) FROM deposit_document d JOIN pending_transaction t ON t.id = d.transaction_id WHERE d.id = '" + id + "' AND t.application_id IS NULL")).isEqualTo(1);

        // It authorised nothing and inscribed nothing.
        JsonNode after = titleView(officer, title);
        assertThat(after.path("title").path("version").asInt()).isEqualTo(before.path("title").path("version").asInt());
        assertThat(after.path("inscriptions")).hasSize(before.path("inscriptions").size());
        assertThat(after.path("transactions").get(0).path("status").asString("")).isEqualTo("HOLDING");

        MockHttpServletResponse sent = officer.browser().send(get(BASE + "/transactions/" + holding + "/documents/" + id + "/content"));
        assertThat(sent.getContentAsByteArray()).isEqualTo(deed);
        assertThat(sent.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(sent.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        for (String action : List.of("REGISTER_DEPOSIT_DOCUMENT_ADDED", "REGISTER_DEPOSIT_DOCUMENT_DOWNLOADED")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND entity_id = ?::uuid AND subjects @> ?::jsonb", Long.class, action, id,
                    "[{\"type\":\"LAND_TITLE\",\"id\":\"" + title + "\"}]")).as(action).isEqualTo(1);
        }
    }

    @Test
    void uploadedFilesPassTheChecksOfAnApplicationsDocuments() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String holding = deposit(officer, title(officer, owner), owner, null);

        assertThat(code(upload(officer, holding, "DEED", "page.pdf", DocumentFixtures.html(), key()))).isEqualTo("TYPE_NOT_ALLOWED");
        assertThat(code(upload(officer, holding, "DEED", "a.pdf", DocumentFixtures.pdf("/AA << /O << /S /JavaScript >> >>"), key()))).isEqualTo("PDF_ACTIVE_CONTENT");
        assertThat(code(upload(officer, holding, "DEED", "a.png", DocumentFixtures.pdf(), key()))).isEqualTo("TYPE_MISMATCH");
        assertThat(code(upload(officer, holding, "DEED", "a.pdf", new byte[0], key()))).isEqualTo("FILE_EMPTY");
        MockHttpServletResponse large = upload(officer, holding, "DEED", "a.pdf", new byte[2_000_000], key());
        assertThat(large.getStatus()).isEqualTo(413);
        assertThat(code(upload(officer, holding, "RANDOM", "a.pdf", DocumentFixtures.pdf(), key()))).isEqualTo("CATEGORY_INVALID");
        assertThat(code(upload(officer, holding, "DEED", "a.pdf", DocumentFixtures.pdf(), ""))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        assertThat(documents(officer, holding)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_DEPOSIT_DOCUMENT_REJECTED' AND reason = 'PDF_ACTIVE_CONTENT' AND entity_id = ?::uuid",
                Long.class, holding)).isEqualTo(1);
    }

    @Test
    void aReplacementIsAuditedAndTheOriginalStaysReadable() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String holding = deposit(officer, title(officer, owner), owner, null);
        byte[] first = DocumentFixtures.pdf("/Title (first)");
        byte[] second = DocumentFixtures.pdf("/Title (second)");
        String original = ok(upload(officer, holding, "DEED", "deed.pdf", first, key()), 201).path("id").asString("");

        assertThat(code(replace(officer, holding, original, "new.pdf", second, "short"))).as("a reason is required").isEqualTo("REASON_REQUIRED");
        String correction = ok(replace(officer, holding, original, "new.pdf", second, "The first scan was the wrong page"), 201).path("id").asString("");
        assertThat(code(replace(officer, holding, original, "other.pdf", second, "A second replacement of the same file"))).isEqualTo("DOCUMENT_ALREADY_REPLACED");

        JsonNode listed = documents(officer, holding);
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).path("replacedById").asString("")).isEqualTo(correction);
        assertThat(listed.get(1).path("replacesId").asString("")).isEqualTo(original);
        assertThat(listed.get(1).path("replaceReason").asString("")).isEqualTo("The first scan was the wrong page");
        assertThat(officer.browser().send(get(BASE + "/transactions/" + holding + "/documents/" + original + "/content")).getContentAsByteArray())
                .as("the original is still served").isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_event WHERE action = 'REGISTER_DEPOSIT_DOCUMENT_REPLACED' AND entity_id = ?::uuid", String.class, correction))
                .isEqualTo("The first scan was the wrong page");
        assertThat(jdbc.queryForObject("SELECT details->>'replaces' FROM audit_event WHERE action = 'REGISTER_DEPOSIT_DOCUMENT_REPLACED' AND entity_id = ?::uuid", String.class, correction))
                .isEqualTo(original);
    }

    @Test
    void documentsStayThroughConflictAndRegistrationAndTheSignatureBindsThem() throws Exception {
        Officer first = conservator();
        Officer second = conservator();
        String seller = party(first, "DEMO Seller");
        String buyer = party(first, "DEMO Buyer");
        String title = title(first, seller);
        String holding = deposit(first, title, buyer, null);
        String arriving = deposit(first, title, party(first, "DEMO Rival"), null);
        byte[] deed = DocumentFixtures.pdf("/Title (deed)");
        String document = ok(upload(first, holding, "DEED", "deed.pdf", deed, key()), 201).path("id").asString("");
        String rival = ok(upload(first, arriving, "DEED", "rival.pdf", DocumentFixtures.pdf("/Title (rival)"), key()), 201).path("id").asString("");
        String conflict = titleView(first, title).path("conflicts").get(0).path("id").asString("");
        ok(postJson(second, "/conflicts/" + conflict + "/resolution", "outcome", "KEEP_HOLDING", "reason", "The first deposit stands",
                "evidenceReferences", List.of("DEMO-DECISION-1")), 200);

        // A closed deposit takes nothing, and its documents are still there.
        assertThat(code(upload(first, arriving, "DEED", "late.pdf", DocumentFixtures.pdf("/Title (late)"), key()))).isEqualTo("TRANSACTION_CLOSED");
        assertThat(code(replace(first, arriving, rival, "late.pdf", deed, "Trying to change a closed deposit"))).isEqualTo("TRANSACTION_CLOSED");
        assertThat(first.browser().send(get(BASE + "/transactions/" + arriving + "/documents/" + rival + "/content")).getStatus()).isEqualTo(200);

        // Evidence added during the ceremony: the signed bytes no longer match, and nothing is registered.
        String transfer = propose(first, title, "TRANSFER", 1, "completesTransactionId", holding, "holders", holder(buyer));
        first.browser().csrf();
        String options = first.browser().send(post(BASE + "/inscriptions/" + transfer + "/signature/options")).getContentAsString();
        ok(upload(first, holding, "IDENTITY", "id.pdf", DocumentFixtures.pdf("/Title (id)"), key()), 201);
        first.browser().csrf();
        MockHttpServletResponse changed = first.browser().send(json(post(BASE + "/inscriptions/" + transfer + "/register"), first.key().get(options)));
        assertThat(code(changed)).isEqualTo("SIGNATURE_INVALID");
        assertThat(titleView(first, title).path("title").path("version").asInt()).isEqualTo(1);

        // Registered: the deposit closes, its documents stay, and Java and SQL agree on the bound bytes.
        signed(first, transfer, "register");
        assertThat(code(upload(first, holding, "DEED", "late.pdf", DocumentFixtures.pdf("/Title (late)"), key()))).isEqualTo("TRANSACTION_CLOSED");
        assertThat(first.browser().send(get(BASE + "/transactions/" + holding + "/documents/" + document + "/content")).getContentAsByteArray()).isEqualTo(deed);
        JsonNode check = verification(first, title);
        assertThat(check.path("intact").asBoolean()).as(check.toString()).isTrue();
        assertThat(check.path("documentsChecked").asInt()).isEqualTo(3);
    }

    @Test
    void theCleanupKeepsADepositsFileAndAChangedFileIsNeverServed() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String title = title(officer, owner);
        String holding = deposit(officer, title, owner, null);
        String id = ok(upload(officer, holding, "DEED", "deed.pdf", DocumentFixtures.pdf("/Title (kept)"), key()), 201).path("id").asString("");
        String storageKey;
        try (Connection super1 = PostgisDatabase.shared().superuserConnection(); Statement st = super1.createStatement();
             var rs = st.executeQuery("SELECT storage_key FROM deposit_document WHERE id = '" + id + "'")) {
            rs.next();
            storageKey = rs.getString(1);
        }
        Path file;
        try (var found = Files.find(documentProperties.directory(), 6, (p, a) -> p.getFileName().toString().equals(storageKey))) {
            file = found.findFirst().orElseThrow();
        }
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(1))));
        SystemActor.call("document-cleanup", documentService::removeOrphans);
        assertThat(Files.exists(file)).as("the cleanup must not remove a deposit's file").isTrue();

        file.toFile().setWritable(true);
        Files.write(file, DocumentFixtures.pdf("/Tampered true"));
        MockHttpServletResponse altered = officer.browser().send(get(BASE + "/transactions/" + holding + "/documents/" + id + "/content"));
        assertThat(altered.getStatus()).isEqualTo(500);
        assertThat(code(altered)).isEqualTo("DOCUMENT_INTEGRITY_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_DEPOSIT_DOCUMENT_INTEGRITY_FAILED' AND entity_id = ?::uuid", Long.class, id)).isEqualTo(1);
        JsonNode check = verification(officer, title);
        assertThat(check.path("intact").asBoolean()).isFalse();
        assertThat(check.path("problems").toString()).contains("no longer matches its hash");
    }

    @Test
    void onlyConservatorsOfTheOfficeReachADepositsDocuments() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String holding = deposit(officer, title(officer, owner), owner, null);
        String id = ok(upload(officer, holding, "DEED", "deed.pdf", DocumentFixtures.pdf(), key()), 201).path("id").asString("");

        Officer elsewhere = conservator("DEMOIT2");
        SignedIn intake = signedIn("INTAKE_OFFICER");
        Browser applicant = applicant();
        for (Browser outsider : List.of(elsewhere.browser(), intake.browser(), applicant)) {
            assertThat(outsider.send(get(BASE + "/transactions/" + holding + "/documents")).getStatus()).isEqualTo(403);
            assertThat(outsider.send(get(BASE + "/transactions/" + holding + "/documents/" + id + "/content")).getStatus()).isEqualTo(403);
            outsider.csrf();
            assertThat(outsider.send(multipart(BASE + "/transactions/" + holding + "/documents").file(new MockMultipartFile("file", "a.pdf", "application/pdf", DocumentFixtures.pdf()))
                    .param("category", "DEED").header("Idempotency-Key", key())).getStatus()).isEqualTo(403);
        }
        assertThat(officer.browser().send(get(BASE + "/transactions/" + holding + "/documents/" + UUID.randomUUID() + "/content")).getStatus()).isEqualTo(404);
    }

    /** One number, read past row-level security, as a test may. */
    private long scalar(String sql) throws Exception {
        try (Connection super1 = PostgisDatabase.shared().superuserConnection(); Statement st = super1.createStatement()) {
            try (var rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // --- Access and rules ---------------------------------------------------------------------------------

    @Test
    void onlyConservatorsOfTheOfficeReachTheRegister() throws Exception {
        Officer officer = conservator();
        String title = title(officer, party(officer, "DEMO Owner"));

        Officer elsewhere = conservator("DEMOIT2");
        assertThat(elsewhere.browser().send(get(BASE + "/titles/" + title)).getStatus()).isEqualTo(403);
        assertThat(elsewhere.browser().send(get(BASE + "/titles").param("office", OFFICE)).getStatus()).isEqualTo(403);
        SignedIn intake = signedIn("INTAKE_OFFICER");
        assertThat(intake.browser().send(get(BASE + "/titles").param("office", OFFICE)).getStatus()).isEqualTo(403);
        assertThat(applicant().send(get(BASE + "/titles/" + title)).getStatus()).isEqualTo(403);
        assertThat(officer.browser().send(get(BASE + "/titles/" + UUID.randomUUID())).getStatus()).as("unknown: no office to hold").isEqualTo(403);
    }

    /** PermissionMatrixIT covers the office endpoints; these take their office from the record itself. */
    @Test
    void everyEndpointOnARecordChecksItsPermissionAtThatRecordsOffice() throws Exception {
        Officer officer = conservator();
        Officer colleague = conservator();
        String owner = party(officer, "DEMO Owner");
        String title = title(officer, owner);
        String holding = deposit(officer, title, owner, null);
        String arriving = deposit(officer, title, party(officer, "DEMO Rival"), null);
        String conflict = titleView(officer, title).path("conflicts").get(0).path("id").asString("");
        String proposal = propose(officer, title, "CHARGE", 1, "charge", Map.of("kind", "SERVITUDE", "blocksTransfer", false));
        assertThat(arriving).isNotBlank();
        List<String> reads = List.of("/titles/" + title, "/titles/" + title + "/extract", "/titles/" + title + "/verification", "/inscriptions/" + proposal);
        List<String> writes = List.of("/inscriptions/" + proposal + "/signature/options", "/inscriptions/" + proposal + "/register",
                "/inscriptions/" + proposal + "/request-confirmation", "/inscriptions/" + proposal + "/confirm", "/inscriptions/" + proposal + "/withdraw",
                "/inscriptions/" + proposal + "/reject", "/transactions/" + holding + "/withdraw", "/transactions/" + holding + "/refuse",
                "/conflicts/" + conflict + "/resolution");
        Officer elsewhere = conservator("DEMOIT2");
        SignedIn intake = signedIn("INTAKE_OFFICER");
        for (Browser outsider : List.of(elsewhere.browser(), intake.browser())) {
            for (String path : reads) {
                assertThat(outsider.send(get(BASE + path)).getStatus()).as(path).isEqualTo(403);
            }
            for (String path : writes) {
                outsider.csrf();
                assertThat(outsider.send(json(post(BASE + path), "{\"reason\":\"A reason long enough\"}")).getStatus()).as(path).isEqualTo(403);
            }
        }
        for (String path : reads) {
            assertThat(colleague.browser().send(get(BASE + path)).getStatus()).as(path).isEqualTo(200);
        }
        assertThat(ok(colleague.browser().send(get(BASE + "/titles").param("office", OFFICE)), 200).findValuesAsString("id")).contains(title);
    }

    @Test
    void aTitlesAuditTimelineIsFoundByItsNumberAndNamesItsConservation() throws Exception {
        Officer officer = conservator();
        String title = title(officer, party(officer, "DEMO Owner"));
        String number = titleView(officer, title).path("title").path("number").asString("");
        SignedIn supervisor = signedIn("SUPERVISOR");
        JsonNode timeline = ok(supervisor.browser().send(get("/api/v1/audit/timeline").param("type", "LAND_TITLE").param("reference", number)), 200);
        assertThat(timeline.path("subjectId").asString("")).isEqualTo(title);
        assertThat(timeline.path("events").findValuesAsString("action")).contains("REGISTER_INSCRIPTION_REGISTERED");
    }

    @Test
    void aRetryReturnsTheSameProposalAndAReusedKeyIsRefused() throws Exception {
        Officer officer = conservator();
        String owner = party(officer, "DEMO Owner");
        String version = parcelVersion();
        String request = proposal(null, "CREATION", 0, "areaM2", 10000, "parcelVersionId", version, "holders", holder(owner));
        String key = key();

        String first = ok(created(officer, "/inscriptions", key, request), 201).path("id").asString("");
        assertThat(ok(created(officer, "/inscriptions", key, request), 201).path("id").asString("")).isEqualTo(first);
        assertThat(code(created(officer, "/inscriptions", key, proposal(null, "CREATION", 0, "areaM2", 99, "parcelVersionId", version, "holders", holder(owner)))))
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(code(created(officer, "/inscriptions", "", request))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        // A signing retry on the finished inscription returns it, by the same officer.
        JsonNode done = signed(officer, first, "register");
        assertThat(ok(sign(officer, officer.key(), first, "register"), 200).path("hash").asString("")).isEqualTo(done.path("hash").asString(""));
    }

    @Test
    void onlySyntheticDataIsAccepted() throws Exception {
        Officer officer = conservator();
        MockHttpServletResponse real = postJson(officer, "/parties", "officeCode", OFFICE, "kind", "NATURAL_PERSON", "fullName", "Jean Dupont",
                "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-1");
        assertThat(real.getStatus()).isEqualTo(422);
        assertThat(code(real)).isEqualTo("REGISTER_INVALID");
    }
}
