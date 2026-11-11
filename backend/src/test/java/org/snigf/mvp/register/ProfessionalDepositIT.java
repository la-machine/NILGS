// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.snigf.mvp.document.DocumentFixtures;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The professional channel through the real endpoints (ADR-041 step 4): the restricted lookup, deposits
 * that are valid and attributable or nothing, duplicates and retries, the professional's own deposits and
 * nobody else's, withdrawal, and the boundary between the professional's endpoints and the conservator's. Since
 * ADR-041 D17 (step 10), the professional reads their deposit's real status and conflict, and nothing of the other.
 */
class ProfessionalDepositIT extends CaseFlowIntegrationTest {

    private static final String PRO = "/api/v1/professional";
    private static final String OFFICE = "DEMOIT";

    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    /** A conservator of the office with their two keys, the first in hand. */
    private record Officer(UUID id, Browser browser, Authenticator key) {
    }

    private Officer conservator() throws Exception {
        String email = email();
        UUID id = insertStaff(email, "CONSERVATOR", OFFICE);
        Browser browser = new Browser();
        browser.login(email, PASSWORD);
        Authenticator key = new Authenticator();
        register(browser, key, "Office key");
        register(browser, new Authenticator(), "Backup key");
        browser.csrf();
        return new Officer(id, browser, key);
    }

    /** A registered title at the office, held by a fresh party; returns its id. */
    private String title(Officer who) throws Exception {
        String holder = ok(sendJson(who.browser(), post("/api/v1/register/parties"), body("officeCode", OFFICE, "kind", "NATURAL_PERSON",
                "fullName", "DEMO Seller", "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-" + UUID.randomUUID())), 201).path("id").asString("");
        String reference = parcel(signedIn("CADASTRAL_OFFICER"), area(), 0);
        String version = jdbc.queryForObject("""
                SELECT v.id::text FROM parcel_version v JOIN parcel p ON p.id = v.parcel_id WHERE p.reference = ? AND v.version_no = 1""", String.class, reference);
        who.browser().csrf();
        String proposal = ok(who.browser().send(json(post("/api/v1/register/inscriptions").header("Idempotency-Key", key()), body("officeCode", OFFICE,
                "kind", "CREATION", "expectedVersion", 0, "authorityType", "SYNTHETIC_DEMO", "authorityReference", "DEMO-AUTH", "reason",
                "A synthetic reason for a title", "evidenceReferences", List.of("DEMO-EVIDENCE-1"), "areaM2", 10000, "parcelVersionId", version,
                "holders", List.of(Map.of("party", holder, "interest", "OWNERSHIP", "shareNum", 1, "shareDen", 1))))), 201).path("id").asString("");
        who.browser().csrf();
        MockHttpServletResponse started = who.browser().send(post("/api/v1/register/inscriptions/" + proposal + "/signature/options"));
        who.browser().csrf();
        return ok(who.browser().send(json(post("/api/v1/register/inscriptions/" + proposal + "/register"), who.key().get(started.getContentAsString()))), 200)
                .path("titleId").asString("");
    }

    private String number(String title) {
        return scalarText("SELECT number FROM land_title WHERE id = '" + title + "'");
    }

    /** A file to send: its category and bytes. */
    private record Part(String category, String name, byte[] bytes) {
    }

    private static Part deed(String marker) {
        return new Part("DEED", "deed.pdf", DocumentFixtures.pdf("/Title (" + marker + ")"));
    }

    private MockHttpServletResponse deposit(SignedIn who, String title, String key, Part... parts) throws Exception {
        return deposit(who.browser(), title, key, parts);
    }

    private MockHttpServletResponse deposit(Browser browser, String title, String key, Part... parts) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(PRO + "/deposits");
        for (Part part : parts) {
            request.file(new MockMultipartFile("files", part.name(), "application/pdf", part.bytes()));
            request.param("categories", part.category());
        }
        request.param("titleId", title).param("kind", "SALE").param("depositorKind", "NATURAL_PERSON").param("depositorName", "DEMO Buyer")
                .param("depositorIdentityKind", "NATIONAL_ID").param("depositorIdentityNumber", "DEMO-ID-BUYER").header("Idempotency-Key", key);
        browser.csrf();
        return browser.send(request);
    }

    private String deposited(SignedIn who, String title, Part... parts) throws Exception {
        return ok(deposit(who, title, key(), parts), 201).path("id").asString("");
    }

    private MockHttpServletResponse withdraw(SignedIn who, String deposit) throws Exception {
        return sendJson(who.browser(), post(PRO + "/deposits/" + deposit + "/withdrawal"), body("reason", "The client cancelled the sale"));
    }

    /** What a professional may read of their own deposit (D17): a field added later fails here until reviewed. */
    private static final List<String> OWN_DEPOSIT_FIELDS = List.of("id", "titleId", "titleNumber", "officeCode", "kind", "status", "conflict",
            "reviewDueAt", "actionRequired", "depositorName", "capacity", "depositedAt", "closedAt", "closedReason", "documents");

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private long scalar(String sql) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String scalarText(String sql) {
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private SignedIn notaryElsewhere() throws Exception {
        insertStaff(email(), "INTAKE_OFFICER", "DEMOIT2");   // makes sure the office exists
        return activeProfessional("NOTARY", "DEMOIT2");
    }

    // --- The lookup (§6) ------------------------------------------------------------------------------------

    @Test
    void theLookupAnswersATitleAtAServedOfficeAndNothingElse() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        String number = number(title);
        SignedIn notary = activeProfessional("NOTARY", OFFICE);

        JsonNode found = ok(notary.browser().send(get(PRO + "/titles").param("number", number)), 200);
        assertThat(found.path("number").asString("")).isEqualTo(number);
        assertThat(found.path("officeCode").asString("")).isEqualTo(OFFICE);
        assertThat(found.path("acceptsDeposit").asBoolean()).isTrue();
        List<String> fields = new ArrayList<>();
        found.propertyNames().forEach(fields::add);
        assertThat(fields).as("no holder, charge, restriction, history or pending deposit").containsExactlyInAnyOrder("id", "number", "officeCode", "acceptsDeposit");

        MockHttpServletResponse unknown = notary.browser().send(get(PRO + "/titles").param("number", "DEMO-TF-DEMOIT-999999-Z"));
        MockHttpServletResponse elsewhere = notaryElsewhere().browser().send(get(PRO + "/titles").param("number", number));
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(elsewhere.getStatus()).as("a title at an office not served answers as an unknown one").isEqualTo(404);
        assertThat(elsewhere.getContentAsString()).isEqualTo(unknown.getContentAsString());
        assertThat(scalar("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_TITLE_LOOKED_UP' AND actor_id = '" + notary.id() + "'")).isEqualTo(2);
    }

    @Test
    void lookupsAreThrottledPerAccount() throws Exception {
        SignedIn notary = activeProfessional("NOTARY", OFFICE);
        int last = 0;
        for (int i = 0; i < 31; i++) {
            last = notary.browser().send(get(PRO + "/titles").param("number", "DEMO-TF-DEMOIT-999999-Z")).getStatus();
        }
        assertThat(last).as("the test profile allows 30 lookups an hour").isEqualTo(429);
    }

    // --- Deposits (§7) ------------------------------------------------------------------------------------

    @Test
    void aDepositWithItsDeedHoldsTheTitleAndConservatorsSeeWhoDepositedIt() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn notary = activeProfessional("NOTARY", OFFICE);

        String id = deposited(notary, title, deed("DEMO deed"), new Part("IDENTITY", "id.pdf", DocumentFixtures.pdf("/Title (DEMO id)")));
        JsonNode mine = ok(notary.browser().send(get(PRO + "/deposits/" + id)), 200);
        assertThat(fields(mine)).as("nothing beyond the reviewed fields").containsExactlyInAnyOrderElementsOf(OWN_DEPOSIT_FIELDS);
        assertThat(mine.path("status").asString("")).as("its real status (D17)").isEqualTo("HOLDING");
        assertThat(mine.path("conflict").asString("")).isEqualTo("NONE");
        assertThat(mine.path("actionRequired").asString("")).isEqualTo("NONE");
        assertThat(mine.path("reviewDueAt").isNull()).as("a review date while open").isFalse();
        assertThat(mine.path("documents").asLong()).isEqualTo(2);
        assertThat(mine.path("capacity").asString("")).isEqualTo("NOTARY");

        JsonNode seen = ok(officer.browser().send(get("/api/v1/register/titles/" + title)), 200).path("transactions").get(0);
        assertThat(seen.path("status").asString("")).isEqualTo("HOLDING");
        assertThat(seen.path("channel").asString("")).isEqualTo("PROFESSIONAL_ACCOUNT");
        assertThat(seen.path("receivedBy").isNull()).as("no conservator received it").isTrue();
        assertThat(seen.path("submittedByAccount").asString("")).isEqualTo(notary.id().toString());
        assertThat(seen.path("capacity").asString("")).isEqualTo("NOTARY");
        assertThat(scalar("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_TRANSACTION_DEPOSITED' AND entity_id = '" + id
                + "' AND details->>'channel' = 'PROFESSIONAL_ACCOUNT' AND details->>'capacity' = 'NOTARY'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_DEPOSIT_DOCUMENT_ADDED' AND details->>'transaction' = '" + id + "'"))
                .isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM inscription WHERE completes_transaction_id = '" + id + "'")).as("depositing registers nothing").isZero();
    }

    @Test
    void aRefusedDepositRecordsNothingAndLocksNothing() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn notary = activeProfessional("NOTARY", OFFICE);

        assertThat(code(deposit(notary, title, key(), new Part("IDENTITY", "id.pdf", DocumentFixtures.pdf())))).isEqualTo("DEED_REQUIRED");
        assertThat(code(deposit(notary, title, key(), deed("a"), new Part("OTHER", "page.pdf", DocumentFixtures.html())))).isEqualTo("TYPE_NOT_ALLOWED");
        assertThat(code(deposit(notary, title, key(), deed("a"), new Part("OTHER", "a.pdf", DocumentFixtures.pdf("/AA << /O << /S /JavaScript >> >>")))))
                .isEqualTo("PDF_ACTIVE_CONTENT");
        assertThat(code(deposit(notary, title, key(), new Part("RANDOM", "a.pdf", DocumentFixtures.pdf()), deed("a")))).isEqualTo("CATEGORY_INVALID");
        assertThat(code(deposit(notary, title, "", deed("a")))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(deposit(notaryElsewhere(), title, key(), deed("a")).getStatus()).as("an office not served").isEqualTo(403);
        assertThat(deposit(activeProfessional("SWORN_SURVEYOR", OFFICE), title, key(), deed("a")).getStatus()).as("a surveyor").isEqualTo(403);

        assertThat(scalar("SELECT count(*) FROM pending_transaction WHERE title_id = '" + title + "'")).isZero();
        assertThat(scalar("SELECT count(*) FROM audit_event WHERE action IN ('REGISTER_REFUSED', 'REGISTER_DEPOSIT_DOCUMENT_REJECTED') AND actor_id = '"
                + notary.id() + "'")).as("each refusal is audited").isEqualTo(4);
        // Nothing locked: the next deposit, at the counter, holds.
        String depositor = ok(sendJson(officer.browser(), post("/api/v1/register/parties"), body("officeCode", OFFICE, "kind", "NATURAL_PERSON",
                "fullName", "DEMO Counter Buyer", "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-" + UUID.randomUUID())), 201).path("id").asString("");
        officer.browser().csrf();
        String counter = ok(officer.browser().send(json(post("/api/v1/register/transactions").header("Idempotency-Key", key()),
                body("titleId", title, "kind", "SALE", "depositorPartyId", depositor, "evidenceReferences", List.of("DEMO-DEED-1")))), 201).path("id").asString("");
        assertThat(scalar("SELECT count(*) FROM pending_transaction WHERE id = '" + counter + "' AND status = 'HOLDING'")).isEqualTo(1);
    }

    @Test
    void aRetryReturnsTheSameDepositAndTheSameDeedAgainIsRefused() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn notary = activeProfessional("NOTARY", OFFICE);
        Part deed = deed("DEMO deed once");
        String key = key();

        String first = ok(deposit(notary, title, key, deed), 201).path("id").asString("");
        assertThat(ok(deposit(notary, title, key, deed), 201).path("id").asString("")).as("the same request, retried").isEqualTo(first);
        MockHttpServletResponse again = deposit(notary, title, key(), deed);
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("DUPLICATE_DEPOSIT");
        assertThat(scalar("SELECT count(*) FROM pending_transaction WHERE title_id = '" + title + "'")).as("no conflict with oneself").isEqualTo(1);
    }

    // --- Their own deposits, and only theirs ------------------------------------------------------------------

    @Test
    void aProfessionalReadsAddsToAndWithdrawsOnlyTheirOwnDeposits() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn first = activeProfessional("NOTARY", OFFICE);
        SignedIn second = activeProfessional("NOTARY", OFFICE);
        String holding = deposited(first, title, deed("DEMO first"));
        String arriving = deposited(second, title, deed("DEMO second"));
        assertThat(scalar("SELECT count(*) FROM transaction_conflict WHERE holding_id = '" + holding + "' AND arriving_id = '" + arriving + "'")).isEqualTo(1);

        assertThat(ok(second.browser().send(get(PRO + "/deposits/" + arriving)), 200).path("status").asString("")).isEqualTo("IN_CONFLICT");
        JsonNode theirs = ok(second.browser().send(get(PRO + "/deposits")), 200);
        assertThat(theirs.findValuesAsString("id")).containsExactly(arriving);
        assertThat(fields(theirs.get(0))).as("the list shows the same fields, no more").containsExactlyInAnyOrderElementsOf(OWN_DEPOSIT_FIELDS);

        String document = ok(first.browser().send(get(PRO + "/deposits/" + holding + "/documents")), 200).get(0).path("id").asString("");
        assertThat(second.browser().send(get(PRO + "/deposits/" + holding)).getStatus()).isEqualTo(404);
        assertThat(second.browser().send(get(PRO + "/deposits/" + holding + "/documents")).getStatus()).isEqualTo(404);
        assertThat(second.browser().send(get(PRO + "/deposits/" + holding + "/documents/" + document + "/content")).getStatus()).isEqualTo(404);
        second.browser().csrf();
        assertThat(second.browser().send(multipart(PRO + "/deposits/" + holding + "/documents").file(new MockMultipartFile("file", "x.pdf", "application/pdf",
                DocumentFixtures.pdf())).param("category", "OTHER").header("Idempotency-Key", key())).getStatus()).isEqualTo(404);
        assertThat(withdraw(second, holding).getStatus()).isEqualTo(404);

        byte[] extra = DocumentFixtures.pdf("/Title (DEMO extra)");
        first.browser().csrf();
        String added = ok(first.browser().send(multipart(PRO + "/deposits/" + holding + "/documents").file(new MockMultipartFile("file", "extra.pdf",
                "application/pdf", extra)).param("category", "OTHER").header("Idempotency-Key", key())), 201).path("id").asString("");
        MockHttpServletResponse download = first.browser().send(get(PRO + "/deposits/" + holding + "/documents/" + added + "/content"));
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentAsByteArray()).isEqualTo(extra);
        assertThat(download.getHeader("Content-Disposition")).startsWith("attachment");

        assertThat(ok(withdraw(first, holding), 200).path("status").asString("")).isEqualTo("WITHDRAWN");
        assertThat(code(withdraw(first, holding))).isEqualTo("TRANSACTION_CLOSED");
        // The other deposit is still in conflict: no deposit becomes valid by a withdrawal.
        assertThat(scalar("SELECT count(*) FROM pending_transaction WHERE id = '" + arriving + "' AND status = 'IN_CONFLICT'")).isEqualTo(1);
    }

    /** ADR-041 D19: the notary only asks; a conservator of the office approves; the notary sees the outcome, never who decided. */
    @Test
    void aNotaryAsksForAReplacementAndAConservatorOfTheOfficeDecides() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn notary = activeProfessional("NOTARY", OFFICE);
        SignedIn other = activeProfessional("NOTARY", OFFICE);
        String holding = deposited(notary, title, deed("DEMO first"));
        String deed = ok(notary.browser().send(get(PRO + "/deposits/" + holding + "/documents")), 200).get(0).path("id").asString("");
        byte[] clearer = DocumentFixtures.pdf("/Title (DEMO clearer)");

        notary.browser().csrf();
        MockHttpServletResponse asked = notary.browser().send(multipart(PRO + "/deposits/" + holding + "/documents/" + deed + "/replacement")
                .file(new MockMultipartFile("file", "deed-clear.pdf", "application/pdf", clearer)).param("reason", "The first scan is unreadable")
                .header("Idempotency-Key", key()));
        String request = ok(asked, 201).path("id").asString("");
        assertThat(ok(notary.browser().send(get(PRO + "/deposits/" + holding + "/documents")), 200)).as("the original stays the current document").hasSize(1);
        JsonNode mine = ok(notary.browser().send(get(PRO + "/deposits/" + holding + "/replacements")), 200);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("status").asString("")).isEqualTo("PENDING");
        assertThat(mine.get(0).path("requestedBySelf").asBoolean()).isTrue();
        assertThat(fields(mine.get(0))).as("no conservator is named, no storage key").doesNotContain("decidedBy", "decidedByName", "requestedBy",
                "requestedByName", "storageKey");
        assertThat(notary.browser().send(get(PRO + "/deposits/" + holding + "/replacements/" + request + "/content")).getContentAsByteArray()).isEqualTo(clearer);
        assertThat(other.browser().send(get(PRO + "/deposits/" + holding + "/replacements")).getStatus()).isEqualTo(404);
        assertThat(other.browser().send(get(PRO + "/deposits/" + holding + "/replacements/" + request + "/content")).getStatus()).isEqualTo(404);

        // The notary cannot decide: the staff endpoint is not theirs.
        assertThat(sendJson(notary.browser(), post("/api/v1/register/transactions/" + holding + "/replacements/" + request + "/decision"),
                body("outcome", "APPROVED")).getStatus()).isEqualTo(403);
        JsonNode staffView = ok(officer.browser().send(get("/api/v1/register/transactions/" + holding + "/replacements")), 200).get(0);
        assertThat(staffView.path("requestedAs").asString("")).isEqualTo("PROFESSIONAL");
        JsonNode approved = ok(sendJson(officer.browser(), post("/api/v1/register/transactions/" + holding + "/replacements/" + request + "/decision"),
                body("outcome", "APPROVED")), 200);
        String result = approved.path("resultDocumentId").asString("");

        JsonNode after = ok(notary.browser().send(get(PRO + "/deposits/" + holding + "/replacements")), 200).get(0);
        assertThat(after.path("status").asString("")).isEqualTo("APPROVED");
        assertThat(after.path("resultDocumentId").asString("")).isEqualTo(result);
        JsonNode documents = ok(notary.browser().send(get(PRO + "/deposits/" + holding + "/documents")), 200);
        assertThat(documents).hasSize(2);
        assertThat(documents.get(1).path("replacesId").asString("")).isEqualTo(deed);
        assertThat(documents.get(1).path("uploadedBySelf").asBoolean()).as("the notary uploaded the replacement").isTrue();

        // A second request on the replacement, withdrawn by the notary.
        notary.browser().csrf();
        String again = ok(notary.browser().send(multipart(PRO + "/deposits/" + holding + "/documents/" + result + "/replacement")
                .file(new MockMultipartFile("file", "deed-3.pdf", "application/pdf", DocumentFixtures.pdf("/Title (DEMO third)")))
                .param("category", "DEED").param("reason", "A page is missing from the copy").header("Idempotency-Key", key())), 201).path("id").asString("");
        assertThat(sendJson(other.browser(), post(PRO + "/deposits/" + holding + "/replacements/" + again + "/withdrawal"), body("reason", "Not mine to withdraw"))
                .getStatus()).isEqualTo(404);
        assertThat(ok(sendJson(notary.browser(), post(PRO + "/deposits/" + holding + "/replacements/" + again + "/withdrawal"), body()), 200)
                .path("status").asString("")).isEqualTo("WITHDRAWN");
        notary.browser().csrf();
        assertThat(code(notary.browser().send(multipart(PRO + "/deposits/" + holding + "/documents/" + result + "/replacement")
                .file(new MockMultipartFile("file", "deed-4.pdf", "application/pdf", DocumentFixtures.pdf("/Title (DEMO fourth)")))
                .param("category", "IDENTITY").param("reason", "Another category for the same file").header("Idempotency-Key", key()))))
                .as("the original's category is kept").isEqualTo("CATEGORY_INVALID");
    }

    @Test
    void bothNotariesSeeTheConflictUnderReviewAndNeitherSeesTheOther() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn first = activeProfessional("NOTARY", OFFICE);
        SignedIn second = activeProfessional("NOTARY", OFFICE);
        String holding = deposited(first, title, deed("DEMO held"));
        String arriving = deposited(second, title, deed("DEMO arriving"));

        JsonNode held = ok(first.browser().send(get(PRO + "/deposits/" + holding)), 200);
        JsonNode inConflict = ok(second.browser().send(get(PRO + "/deposits/" + arriving)), 200);
        assertThat(held.path("status").asString("") + "/" + held.path("conflict").asString("")).as("the holding side").isEqualTo("HOLDING/UNDER_REVIEW");
        assertThat(inConflict.path("status").asString("") + "/" + inConflict.path("conflict").asString("")).as("the arriving side")
                .isEqualTo("IN_CONFLICT/UNDER_REVIEW");
        assertThat(fields(held)).containsExactlyInAnyOrderElementsOf(OWN_DEPOSIT_FIELDS);
        assertThat(fields(inConflict)).containsExactlyInAnyOrderElementsOf(OWN_DEPOSIT_FIELDS);
        assertThat(held.toString()).as("nothing of the other deposit or its notary").doesNotContain(arriving).doesNotContain(second.id().toString());
        assertThat(inConflict.toString()).as("nothing of the other deposit or its notary").doesNotContain(holding).doesNotContain(first.id().toString());

        // A conservator keeps the holding deposit: the arriving notary reads the effect, not the conservators' reason.
        String conflict = scalarText("SELECT id::text FROM transaction_conflict WHERE arriving_id = '" + arriving + "'");
        ok(sendJson(officer.browser(), post("/api/v1/register/conflicts/" + conflict + "/resolution"), body("outcome", "KEEP_HOLDING",
                "reason", "The deed of the other notary was first", "evidenceReferences", List.of("DEMO-DECISION-1"))), 200);
        JsonNode refused = ok(second.browser().send(get(PRO + "/deposits/" + arriving)), 200);
        assertThat(refused.path("status").asString("")).isEqualTo("REFUSED");
        assertThat(refused.path("conflict").asString("")).isEqualTo("RESOLVED");
        assertThat(refused.path("closedReason").isNull()).as("the resolution's reason is the conservators', and may name the other party").isTrue();
        assertThat(refused.path("reviewDueAt").isNull()).isTrue();
        assertThat(ok(first.browser().send(get(PRO + "/deposits/" + holding)), 200).path("conflict").asString("")).isEqualTo("RESOLVED");
    }

    @Test
    void aWithdrawalDuringAConflictIsMarkedInTheAuditTrail() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn holder = activeProfessional("NOTARY", OFFICE);
        SignedIn prober = activeProfessional("NOTARY", OFFICE);
        String held = deposited(holder, title, deed("DEMO held"));
        String probe = deposited(prober, title, deed("DEMO probe"));

        JsonNode withdrawn = ok(withdraw(prober, probe), 200);
        assertThat(withdrawn.path("status").asString("")).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.path("conflict").asString("")).as("the conflict waits for conservators").isEqualTo("UNDER_REVIEW");
        assertThat(scalar("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_TRANSACTION_WITHDRAWN' AND entity_id = '" + probe
                + "' AND details->>'withdrawnDuringConflict' = 'true'")).isEqualTo(1);
        assertThat(ok(holder.browser().send(get(PRO + "/deposits/" + held)), 200).path("conflict").asString("")).isEqualTo("UNDER_REVIEW");
        // Outside a conflict, the flag is false.
        String alone = deposited(prober, title(officer), deed("DEMO alone"));
        ok(withdraw(prober, alone), 200);
        assertThat(scalar("SELECT count(*) FROM audit_event WHERE action = 'REGISTER_TRANSACTION_WITHDRAWN' AND entity_id = '" + alone
                + "' AND details->>'withdrawnDuringConflict' = 'false'")).isEqualTo(1);
    }

    @Test
    void conservatorsAndProfessionalsKeepToTheirOwnEndpoints() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn notary = activeProfessional("NOTARY", OFFICE);
        String id = deposited(notary, title, deed("DEMO boundary"));

        officer.browser().csrf();
        assertThat(officer.browser().send(get(PRO + "/titles").param("number", number(title))).getStatus()).isEqualTo(403);
        assertThat(officer.browser().send(get(PRO + "/deposits")).getStatus()).isEqualTo(403);
        assertThat(deposit(officer.browser(), title, key(), deed("DEMO officer")).getStatus()).isEqualTo(403);

        assertThat(notary.browser().send(get("/api/v1/register/titles/" + title)).getStatus()).isEqualTo(403);
        assertThat(notary.browser().send(get("/api/v1/register/transactions/" + id + "/documents")).getStatus()).isEqualTo(403);
        notary.browser().csrf();
        assertThat(notary.browser().send(json(post("/api/v1/register/transactions/" + id + "/refuse"), body("reason", "Refusing my own deposit"))).getStatus())
                .isEqualTo(403);
        // A conservator refuses it, as a counter deposit.
        assertThat(ok(sendJson(officer.browser(), post("/api/v1/register/transactions/" + id + "/refuse"), body("reason", "Reviewed: the deed is incomplete")), 200)
                .path("status").asString("")).isEqualTo("REFUSED");
        assertThat(ok(notary.browser().send(get(PRO + "/deposits/" + id)), 200).path("closedReason").asString(""))
                .as("a refusal outside a conflict decision keeps its reason").isEqualTo("Reviewed: the deed is incomplete");
    }

    @Test
    void suspensionClosesTheChannelAtTheNextRequest() throws Exception {
        Officer officer = conservator();
        String title = title(officer);
        SignedIn notary = activeProfessional("NOTARY", OFFICE);
        String id = deposited(notary, title, deed("DEMO suspended"));
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        assertThat(sendJson(admin.browser(), post("/api/v1/admin/professionals/" + notary.id() + "/suspension"), body("reason", "Deposits reported as abusive"))
                .getStatus()).isEqualTo(204);

        assertThat(notary.browser().send(get(PRO + "/titles").param("number", number(title))).getStatus()).isEqualTo(403);
        assertThat(notary.browser().send(get(PRO + "/deposits/" + id)).getStatus()).isEqualTo(403);
        assertThat(deposit(notary, title, key(), deed("DEMO again")).getStatus()).isEqualTo(403);
        assertThat(withdraw(notary, id).getStatus()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM pending_transaction WHERE id = '" + id + "' AND status = 'HOLDING'")).as("the deposit stays for conservators")
                .isEqualTo(1);
    }
}
