// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.ApplicantPayloadSchema;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Procedure A's registration through the real endpoints (ADR-042 step 5, §4; ADR-043 step 5a): two co-owners with unequal
 * shares, applied for by a representative who owns nothing; their persons transcribed and checked at intake, with the
 * refusals of the four-eyes rule through the API; the title proposed from the decided ownership statement and signed by
 * two conservators. Then, as in step 5: a procedure A case taken through its four
 * stages, its publication and its approval, then "Approved, to register", the conservator's proposal from the case and
 * their signature, which creates an IMMATRICULATION title naming the case. The case stays APPROVED; the applicant reads
 * the title's number only; registration issues no document; a second CREATION, an objection afterwards, a conservator who
 * handled the case and a case of the generic type are refused. The signatures are genuine (webauthn4j emulators), and the
 * title verifies, so the signed bytes naming the case agree between the database and the code.
 */
class CaseRegistrationIT extends CaseFlowIntegrationTest {

    private static final String REGISTER = "/api/v1/register";

    /** A conservator with their key in hand. */
    private record Conservator(UUID id, Browser browser, Authenticator key) {
    }

    private Conservator conservator() throws Exception {
        return conservator(null);
    }

    /** A conservator at the test office, also holding {@code otherRole} when not null, signed in after both are given. */
    private Conservator conservator(String otherRole) throws Exception {
        String email = email();
        UUID id = insertStaff(email, "CONSERVATOR", "DEMOIT");
        if (otherRole != null) {
            addRole(id, otherRole);
        }
        Browser browser = new Browser();
        browser.login(email, PASSWORD);
        Authenticator key = new Authenticator();
        register(browser, key, "Office key");
        register(browser, new Authenticator(), "Backup key");
        browser.csrf();
        return new Conservator(id, browser, key);
    }

    private MockHttpServletResponse record(SignedIn who, String id, String stage, Map<String, Object> details, byte[]... files) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("outcome", "COMPLETED");
        record.put("finding", "Recorded for the registration journey");
        record.put("details", details);
        who.browser().csrf();
        MockMultipartHttpServletRequestBuilder request = multipart(PROCEDURES + "/" + id + "/stages/" + stage);
        request.file(new MockMultipartFile("record", "", "application/json", json.writeValueAsString(record).getBytes(StandardCharsets.UTF_8)));
        for (byte[] file : files) {
            request.file(new MockMultipartFile("files", "report.pdf", "application/pdf", file));
        }
        MockHttpServletResponse response = who.browser().send(request);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    private static void periodOver(String id) throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.Statement st = su.createStatement()) {
            st.execute("ALTER TABLE snigf.procedure_notice DISABLE TRIGGER procedure_notice_append_only");
            st.execute("UPDATE snigf.procedure_notice SET opens_at = opens_at - interval '31 days', closes_at = closes_at - interval '31 days'"
                    + " WHERE application_id = '" + UUID.fromString(id) + "'");
            st.execute("ALTER TABLE snigf.procedure_notice ENABLE TRIGGER procedure_notice_append_only");
            su.commit();
        }
    }

    private void approved(String id) throws Exception {
        approved(id, signedIn("REVIEWER").browser());
    }

    /** Recommends (as {@code reviewer}) and decides an approval, citing the first document version the authority reads. */
    private void approved(String id, Browser reviewer) throws Exception {
        SignedIn authority = signedIn("DECISION_AUTHORITY");
        assertThat(sendJson(reviewer, post(REVIEWS + "/" + id + "/review-claim"), "{}").getStatus()).isEqualTo(200);
        long version = json.readTree(reviewer.send(get(REVIEWS + "/" + id)).getContentAsString()).path("version").asLong();
        MockHttpServletResponse recommended = sendJson(reviewer, post(REVIEWS + "/" + id + "/recommendation"), body("expectedVersion", version,
                "recommendation", "RECOMMEND_APPROVAL", "finding", "The procedure is complete and nothing is contested"));
        assertThat(recommended.getStatus()).as(recommended.getContentAsString()).isEqualTo(200);
        assertThat(act(authority, id, "decision-claim", "{}").getStatus()).isEqualTo(200);
        String cited = json.readTree(authority.browser().send(get("/api/v1/assigned-cases/" + id + "/documents"))
                .getContentAsString()).path("categories").get(0).path("versions").get(0).path("id").asString("");
        MockHttpServletResponse decided = act(authority, id, "decision", body("expectedVersion", version(authority, id),
                "outcome", "APPROVED", "grounds", "Occupation proven, boundaries agreed, no objection", "citedVersions", List.of(cited)));
        assertThat(decided.getStatus()).as(decided.getContentAsString()).isEqualTo(200);
    }

    private MockHttpServletResponse propose(Browser who, String id, String key) throws Exception {
        who.csrf();
        return who.send(json(post(REGISTER + "/cases/" + id + "/creation").header("Idempotency-Key", key), json.writeValueAsString(Map.of(
                "reason", "Approved immatriculation, procedure complete (synthetic)"))));
    }

    /** One signing act ({@code register}, {@code request-confirmation} or {@code confirm}) with the conservator's own key. */
    private MockHttpServletResponse sign(Conservator who, String inscription, String act) throws Exception {
        who.browser().csrf();
        MockHttpServletResponse started = who.browser().send(post(REGISTER + "/inscriptions/" + inscription + "/signature/options"));
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(200);
        who.browser().csrf();
        return who.browser().send(json(post(REGISTER + "/inscriptions/" + inscription + "/" + act), who.key().get(started.getContentAsString())));
    }

    /** The applicant p1 represents two heirs, p2 (two thirds) and p3 (one third), and owns nothing (ADR-043 D35, D36). */
    private static Map<String, Object> severalDeclaration() {
        return Map.of("capacity", "ON_BEHALF_OF_SEVERAL",
                "persons", List.of(
                        Map.of("ref", "p1", "kind", "NATURAL_PERSON", "roles", List.of("APPLICANT", "REPRESENTATIVE"), "fullName", "DEMO Representative Ateba",
                                "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-ID-REP-0001", "phone", "+237 690000001"),
                        Map.of("ref", "p2", "kind", "NATURAL_PERSON", "roles", List.of("INTENDED_OWNER"), "fullName", "DEMO Heir Mvondo",
                                "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-ID-HEIR-0002"),
                        Map.of("ref", "p3", "kind", "NATURAL_PERSON", "roles", List.of("INTENDED_OWNER"), "fullName", "DEMO Heir Essomba",
                                "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-ID-HEIR-0003")),
                "shares", List.of(Map.of("person", "p2", "interest", "CO_OWNERSHIP", "shareNum", 2, "shareDen", 3),
                        Map.of("person", "p3", "interest", "CO_OWNERSHIP", "shareNum", 1, "shareDen", 3)),
                "mandates", List.of(Map.of("representative", "p1", "represents", List.of("p2", "p3"), "scope", "APPLY")));
    }

    /** Intake after one identity was transcribed by hand above: the rest as {@link #partiesReady}, then that one checked too. */
    private void partiesReadyAfter(String id, SignedIn recorder, SignedIn checker, String alreadyRecorded) throws Exception {
        String identityFile = partyFile(recorder, id, "IDENTITY_DOCUMENT");
        String ownershipFile = partyFile(recorder, id, "OWNERSHIP_EVIDENCE");
        String mandateFile = partyFile(recorder, id, "MANDATE");
        JsonNode view = parties(recorder, id);
        for (JsonNode person : view.path("persons")) {
            JsonNode latest = person.path("identities").get(0);
            if (!"DECLARED".equals(latest.path("source").asString(""))) {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fullName", latest.path("fullName").asString(""));
            body.put("identityKind", latest.path("identityKind").asString(""));
            body.put("identityNumber", latest.path("identityNumber").asString(""));
            if (latest.hasNonNull("phone")) {
                body.put("phone", latest.path("phone").asString(""));
            }
            body.put("sourceFile", identityFile);
            body.put("supersedes", latest.path("id").asString(""));
            assertThat(sendJson(recorder.browser(), post(PROCEDURES + "/" + id + "/parties/persons/" + person.path("id").asString("") + "/identity"),
                    json.writeValueAsString(body)).getStatus()).isEqualTo(200);
        }
        JsonNode statement = view.path("statements").get(0);
        java.util.Map<String, String> ids = new java.util.HashMap<>();
        view.path("persons").forEach(p -> ids.put(p.path("ref").asString(""), p.path("id").asString("")));
        // Unequal shares from the documents, never split by default: a statement that does not total one is refused.
        MockHttpServletResponse wrong = sendJson(recorder.browser(), post(PROCEDURES + "/" + id + "/parties/ownership"), json.writeValueAsString(Map.of(
                "shares", List.of(Map.of("person", ids.get("p2"), "interest", "CO_OWNERSHIP", "shareNum", 1, "shareDen", 2, "basisFile", ownershipFile),
                        Map.of("person", ids.get("p3"), "interest", "CO_OWNERSHIP", "shareNum", 1, "shareDen", 3, "basisFile", ownershipFile)),
                "supersedes", statement.path("id").asString(""))));
        assertThat(code(wrong)).isEqualTo("SHARES_MUST_TOTAL_ONE");
        assertThat(sendJson(recorder.browser(), post(PROCEDURES + "/" + id + "/parties/ownership"), json.writeValueAsString(Map.of(
                "shares", List.of(Map.of("person", ids.get("p2"), "interest", "CO_OWNERSHIP", "shareNum", 2, "shareDen", 3, "basisFile", ownershipFile),
                        Map.of("person", ids.get("p3"), "interest", "CO_OWNERSHIP", "shareNum", 1, "shareDen", 3, "basisFile", ownershipFile)),
                "supersedes", statement.path("id").asString("")))).getStatus()).isEqualTo(200);
        JsonNode mandate = view.path("mandates").get(0);
        assertThat(sendJson(recorder.browser(), post(PROCEDURES + "/" + id + "/parties/persons/" + ids.get("p1") + "/mandate"), json.writeValueAsString(Map.of(
                "represents", List.of(ids.get("p2"), ids.get("p3")), "scope", "APPLY", "documentFile", mandateFile,
                "supersedes", mandate.path("id").asString("")))).getStatus()).isEqualTo(200);
        JsonNode recorded = parties(checker, id);
        for (JsonNode person : recorded.path("persons")) {
            check(checker, id, "IDENTITY", person.path("identities").get(0).path("id").asString(""));
        }
        check(checker, id, "OWNERSHIP", recorded.path("statements").get(0).path("id").asString(""));
        check(checker, id, "MANDATE", recorded.path("mandates").get(0).path("id").asString(""));
        JsonNode done = parties(checker, id);
        assertThat(done.path("problems")).isEmpty();
        assertThat(done.path("persons").get(1).path("identities").get(0).path("check").path("outcome").asString("")).isEqualTo("MATCHES");
        assertThat(done.path("persons").get(1).path("identities").get(0).path("verification").asString("")).isEqualTo("UNVERIFIED_DEMO");
        assertThat(alreadyRecorded).isNotBlank();
    }

    private JsonNode queued(Browser who, String reference) throws Exception {
        for (JsonNode c : json.readTree(who.send(get(REGISTER + "/case-queue")).getContentAsString())) {
            if (c.path("reference").asString("").equals(reference)) {
                return c;
            }
        }
        return null;
    }

    @Test
    void twoCoOwnersAppliedForByARepresentativeBecomeTheHoldersOfATitleSignedByTwoConservators() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String parcel = parcel(cadastral, area(), 0);

        // The declaration: checked as the applicant goes, and at submission.
        Map<String, Object> incomplete = new LinkedHashMap<>(severalDeclaration());
        incomplete.remove("mandates");
        String draft = json.readTree(sendJson(applicant, post(APPS).header("Idempotency-Key", key()),
                "{\"typeCode\":\"DEMO_IMMATRICULATION\",\"data\":{}}").getContentAsString()).path("id").asString("");
        JsonNode saved = json.readTree(sendJson(applicant, put(APPS + "/" + draft), json.writeValueAsString(Map.of("expectedVersion", 0,
                "data", Map.of(), "declaration", incomplete))).getContentAsString());
        assertThat(saved.path("declarationProblems").toString()).contains("REPRESENTATIVE_WITHOUT_MANDATE:p1", "MANDATE_MISSING:p2", "MANDATE_MISSING:p3");

        String id = immatriculationSubmitted(applicant, parcel, severalDeclaration());
        paid(applicant, id);
        SignedIn recorder = signedIn("INTAKE_OFFICER");
        SignedIn checker = signedIn("INTAKE_OFFICER");
        JsonNode declared = parties(recorder, id);
        assertThat(declared.path("problems").toString()).contains("IDENTITY_NOT_RECORDED:p2", "OWNERSHIP_NOT_RECORDED", "MANDATE_NOT_RECORDED:p1");
        assertThat(declared.path("persons").findValuesAsString("ref")).containsExactly("p1", "p2", "p3");
        // Not accepted before the persons are transcribed and checked (D38).
        long version = json.readTree(recorder.browser().send(get("/api/v1/cases/" + id)).getContentAsString()).path("version").asLong();
        MockHttpServletResponse early = sendJson(recorder.browser(), post("/api/v1/cases/" + id + "/acceptance"), "{\"expectedVersion\":" + version + "}");
        assertThat(early.getStatus()).isEqualTo(409);
        assertThat(code(early)).isEqualTo("PARTIES_INCOMPLETE");
        // The recorder never checks their own transcription; the applicant never records.
        String file = partyFile(recorder, id, "IDENTITY_DOCUMENT");
        JsonNode p2 = declared.path("persons").get(1);
        MockHttpServletResponse transcribed = sendJson(recorder.browser(), post(PROCEDURES + "/" + id + "/parties/persons/" + p2.path("id").asString("")
                + "/identity"), json.writeValueAsString(Map.of("fullName", "DEMO Heir Mvondo", "identityKind", "NATIONAL_ID", "identityNumber",
                "DEMO-ID-HEIR-0002", "sourceFile", file, "supersedes", p2.path("identities").get(0).path("id").asString(""))));
        assertThat(transcribed.getStatus()).as(transcribed.getContentAsString()).isEqualTo(200);
        String recorded = json.readTree(transcribed.getContentAsString()).path("persons").get(1).path("identities").get(0).path("id").asString("");
        MockHttpServletResponse own = sendJson(recorder.browser(), post(PROCEDURES + "/" + id + "/parties/checks"),
                json.writeValueAsString(Map.of("targetKind", "IDENTITY", "targetId", recorded, "outcome", "MATCHES")));
        assertThat(own.getStatus()).isEqualTo(403);
        assertThat(code(own)).isEqualTo("RECORDER_CANNOT_CHECK");
        assertThat(applicant.send(get(PROCEDURES + "/" + id + "/parties")).getStatus()).as("the applicant reads their own view only").isEqualTo(403);
        partiesReadyAfter(id, recorder, checker, recorded);
        JsonNode mineAtIntake = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        assertThat(mineAtIntake.path("parties").findValuesAsString("status")).containsOnly("MATCHES_DOCUMENT");
        assertThat(mineAtIntake.path("parties").findValuesAsString("verification")).containsOnly("UNVERIFIED_DEMO");
        ApplicantPayloadSchema.assertSafe(mineAtIntake, recorder.email(), checker.email());
        accepted(signedIn("INTAKE_OFFICER"), id);
        String reference = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString()).path("reference").asString("");
        Conservator conservator = conservator();
        Conservator second = conservator();
        String today = LocalDate.now().toString();

        // Under review: nothing to register yet.
        assertThat(code(propose(conservator.browser(), id, key()))).isEqualTo("CASE_NOT_APPROVED");
        assertThat(queued(conservator.browser(), reference)).isNull();

        // The procedure, its publication, its period, its approval.
        record(cadastral, id, "CADASTRAL_REVIEW", Map.of());
        record(cadastral, id, "FIELD_VISIT", Map.of("visitedOn", today, "members", List.of(Map.of("name", "DEMO Chief", "capacity", "Village chief"))));
        record(signedIn("COMMISSION_SECRETARY"), id, "COMMISSION_REPORT", Map.of("reportReference", "PV-DEMO-501", "reportDate", today,
                "signatories", List.of(Map.of("name", "DEMO Chair", "capacity", "Commission chair"))), DocumentFixtures.pdf("/Lang (pv)"));
        record(signedIn("INTAKE_OFFICER"), id, "PUBLICATION", Map.of());
        periodOver(id);
        // The reviewer also holds the conservator's role: having handled the case, they never propose its title.
        Conservator handler = conservator("REVIEWER");
        approved(id, handler.browser());

        // "Approved, to register".
        JsonNode entry = queued(conservator.browser(), reference);
        assertThat(entry).as("the approved case waits for its registration").isNotNull();
        assertThat(entry.path("mayPropose").asBoolean()).isTrue();
        assertThat(entry.path("problems")).isEmpty();
        assertThat(entry.path("parcelReference").asString("")).isEqualTo(parcel);
        assertThat(entry.path("owners").asString("")).as("whom the title will name").isEqualTo("DEMO Heir Mvondo (2/3); DEMO Heir Essomba (1/3)");
        assertThat(cadastral.browser().send(get(REGISTER + "/case-queue")).getStatus()).as("a conservator's queue only").isEqualTo(403);

        // Whoever handled the case never proposes its title, even holding the permission.
        assertThat(queued(handler.browser(), reference).path("mayPropose").asBoolean()).isFalse();
        MockHttpServletResponse byHandler = propose(handler.browser(), id, key());
        assertThat(byHandler.getStatus()).isEqualTo(403);
        assertThat(code(byHandler)).isEqualTo("CASE_HANDLER_CANNOT_REGISTER");

        // The proposal: the holders come from the decided statement; a retry gives the same proposal.
        String retryKey = key();
        MockHttpServletResponse proposed = propose(conservator.browser(), id, retryKey);
        assertThat(proposed.getStatus()).as(proposed.getContentAsString()).isEqualTo(201);
        String inscription = json.readTree(proposed.getContentAsString()).path("id").asString("");
        assertThat(json.readTree(propose(conservator.browser(), id, retryKey).getContentAsString()).path("id").asString(""))
                .as("a retry adds nothing").isEqualTo(inscription);
        assertThat(code(propose(conservator.browser(), id, key()))).isEqualTo("CREATION_ALREADY_PROPOSED");
        JsonNode proposal = json.readTree(conservator.browser().send(get(REGISTER + "/inscriptions/" + inscription)).getContentAsString());
        assertThat(proposal.path("kind").asString("")).isEqualTo("CREATION");
        assertThat(proposal.path("status").asString("")).isEqualTo("PROPOSED");
        assertThat(proposal.path("authorityType").asString("")).isEqualTo("DECISION");
        assertThat(proposal.path("authorityReference").asString("")).isEqualTo(reference);
        assertThat(proposal.path("applicationId").asString("")).isEqualTo(id);
        assertThat(proposal.path("holders").findValuesAsString("partyName")).as("the two owners, never the representative")
                .containsExactlyInAnyOrder("DEMO Heir Mvondo", "DEMO Heir Essomba");
        assertThat(proposal.path("holders").toString()).doesNotContain("DEMO Representative Ateba");
        assertThat(proposal.path("evidenceVersions")).hasSize(1);
        assertThat(queued(conservator.browser(), reference).path("inscriptionId").asString("")).isEqualTo(inscription);

        // Both signers examine the approved owners and their evidence first: read-only, audited, never a phone (owner's review).
        MockHttpServletResponse evidenceResponse = conservator.browser().send(get(REGISTER + "/cases/" + id + "/evidence"));
        assertThat(evidenceResponse.getStatus()).as(evidenceResponse.getContentAsString()).isEqualTo(200);
        JsonNode evidence = json.readTree(evidenceResponse.getContentAsString());
        assertThat(evidence.path("owners").findValuesAsString("fullName")).containsExactlyInAnyOrder("DEMO Heir Mvondo", "DEMO Heir Essomba");
        assertThat(evidence.path("owners").findValuesAsString("verification")).containsOnly("UNVERIFIED_DEMO");
        assertThat(evidence.path("owners").get(0).path("check").path("outcome").asString("")).isEqualTo("MATCHES");
        assertThat(evidence.path("mandates")).hasSize(1);
        assertThat(evidenceResponse.getContentAsString()).doesNotContain("+237", "phone", "postal");
        assertThat(second.browser().send(get(REGISTER + "/cases/" + id + "/evidence")).getStatus()).as("the second signer too").isEqualTo(200);
        String cited = evidence.path("owners").get(0).path("sourceFile").asString("");
        MockHttpServletResponse scan = conservator.browser().send(get(REGISTER + "/cases/" + id + "/evidence/files/" + cited));
        assertThat(scan.getStatus()).isEqualTo(200);
        assertThat(scan.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(recorder.browser().send(get(REGISTER + "/cases/" + id + "/evidence")).getStatus()).as("only for the registration task")
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'CASE_PARTIES_READ_FOR_REGISTRATION' AND entity_id = ?::uuid",
                Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'CASE_PARTY_FILE_DOWNLOADED' AND entity_id = ?::uuid"
                + " AND details->>'purpose' = 'REGISTRATION'", Integer.class, id)).isEqualTo(1);

        // Two signatures (ADR-043 D40): one is refused; the proposer signs first, a second conservator confirms.
        assertThat(code(sign(conservator, inscription, "register"))).isEqualTo("DUAL_CONTROL_REQUIRED");
        assertThat(sign(conservator, inscription, "request-confirmation").getStatus()).isEqualTo(200);
        assertThat(code(sign(conservator, inscription, "confirm"))).as("never the proposer").isEqualTo("SELF_CONFIRMATION");
        MockHttpServletResponse registered = sign(second, inscription, "confirm");
        assertThat(registered.getStatus()).as(registered.getContentAsString()).isEqualTo(200);
        String title = json.readTree(registered.getContentAsString()).path("titleId").asString("");
        JsonNode view = json.readTree(conservator.browser().send(get(REGISTER + "/titles/" + title)).getContentAsString());
        assertThat(view.path("title").path("origin").asString("")).isEqualTo("IMMATRICULATION");
        assertThat(view.path("title").path("parcelReference").asString("")).isEqualTo(parcel);
        assertThat(view.path("title").path("holders")).hasSize(2);
        assertThat(view.path("title").path("holders").findValuesAsString("interest")).containsOnly("CO_OWNERSHIP");
        String number = view.path("title").path("number").asString("");
        JsonNode verified = json.readTree(conservator.browser().send(get(REGISTER + "/titles/" + title + "/verification")).getContentAsString());
        assertThat(verified.path("intact").asBoolean()).as(verified.toString()).isTrue();
        assertThat(verified.path("signaturesChecked").asInt()).as("both signatures verify").isEqualTo(2);
        // A holder changed directly in the database, behind the signatures, is detected by the title's verification.
        String holderParty = view.path("title").path("holders").get(0).path("partyId").asString("");
        superuser("ALTER TABLE snigf.register_party DISABLE TRIGGER register_party_append_only;"
                + " UPDATE snigf.register_party SET identity_number = 'DEMO-ID-FORGED' WHERE id = '" + UUID.fromString(holderParty) + "';"
                + " ALTER TABLE snigf.register_party ENABLE TRIGGER register_party_append_only;");
        JsonNode tampered = json.readTree(conservator.browser().send(get(REGISTER + "/titles/" + title + "/verification")).getContentAsString());
        assertThat(tampered.path("intact").asBoolean()).as("a party changed behind the signatures").isFalse();
        assertThat(tampered.path("problems").toString()).contains("differs from what was signed");
        superuser("ALTER TABLE snigf.register_party DISABLE TRIGGER register_party_append_only;"
                + " UPDATE snigf.register_party SET identity_number = (SELECT i.identity_number FROM snigf.case_person_identity i"
                + " WHERE i.id = source_identity_id) WHERE id = '" + UUID.fromString(holderParty) + "';"
                + " ALTER TABLE snigf.register_party ENABLE TRIGGER register_party_append_only;");
        assertThat(json.readTree(conservator.browser().send(get(REGISTER + "/titles/" + title + "/verification")).getContentAsString())
                .path("intact").asBoolean()).as("restored, it verifies again").isTrue();

        // A case is not a title, and registering issues nothing.
        JsonNode mine = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        assertThat(mine.path("state").asString("")).isEqualTo("APPROVED");
        assertThat(mine.path("registration").path("titleNumber").asString("")).isEqualTo(number);
        ApplicantPayloadSchema.assertSafe(mine, "Approved immatriculation, procedure complete");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_document WHERE subject_id = ?::uuid AND doc_type <> 'DEMO_SUBMISSION_ACKNOWLEDGMENT'",
                Integer.class, id)).as("no title document, no notice yet (D27, D29)").isZero();

        // Afterwards: no second CREATION, no objection, and the case leaves the queue.
        assertThat(code(propose(conservator.browser(), id, key()))).isEqualTo("CASE_ALREADY_REGISTERED");
        SignedIn counter = signedIn("INTAKE_OFFICER");
        counter.browser().csrf();
        MockHttpServletResponse objected = counter.browser().send(multipart(PROCEDURES + "/" + id + "/objections")
                .file(new MockMultipartFile("objection", "", "application/json", json.writeValueAsString(Map.of("objectorName", "DEMO Neighbour Late",
                        "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-ID-9", "interest", "NEIGHBOUR",
                        "statement", "The boundary crosses my field (synthetic)")).getBytes(StandardCharsets.UTF_8)))
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(code(objected)).isEqualTo("CASE_REGISTERED");
        assertThat(queued(conservator.browser(), reference)).isNull();

        // Audited, on the case's timeline as well as the title's.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'PROCEDURE_REGISTRATION_PROPOSED' AND entity_id = ?::uuid",
                Integer.class, inscription)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event WHERE action IN ('PROCEDURE_REGISTRATION_PROPOSED', 'REGISTER_INSCRIPTION_REGISTERED')
                   AND entity_id = ?::uuid AND subjects @> jsonb_build_array(jsonb_build_object('type', 'APPLICATION', 'id', ?::text))""",
                Integer.class, inscription, id)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT reason FROM audit_event WHERE action = 'REGISTER_REFUSED' AND entity_id = ?::uuid", String.class, id))
                .contains("CASE_NOT_APPROVED", "CASE_HANDLER_CANNOT_REGISTER", "CREATION_ALREADY_PROPOSED", "CASE_ALREADY_REGISTERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'CASE_PARTY_CHECKED' AND entity_id = ?::uuid", Integer.class, id))
                .as("each check audited").isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND (coalesce(details::text, '') LIKE '%DEMO-ID-HEIR%'"
                + " OR coalesce(reason, '') LIKE '%DEMO-ID-HEIR%')", Integer.class, id)).as("never an identity number in the trail").isZero();
    }

    /** A correction of the stage's latest record, as a reconsideration asks (ADR-043 step 5b). */
    private void recordAgain(SignedIn who, String id, String stage, Map<String, Object> details, byte[]... files) throws Exception {
        String latest = "";
        for (JsonNode st : json.readTree(who.browser().send(get(PROCEDURES + "/" + id)).getContentAsString()).path("stages")) {
            if (st.path("code").asString("").equals(stage)) {
                latest = st.path("records").get(0).path("id").asString("");
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("outcome", "COMPLETED");
        record.put("finding", "Recorded again in the reconsideration round");
        record.put("details", details);
        record.put("supersedes", latest);
        record.put("correctionReason", "Reopened by the reconsideration after an upheld objection");
        who.browser().csrf();
        MockMultipartHttpServletRequestBuilder request = multipart(PROCEDURES + "/" + id + "/stages/" + stage);
        request.file(new MockMultipartFile("record", "", "application/json", json.writeValueAsString(record).getBytes(StandardCharsets.UTF_8)));
        for (byte[] file : files) {
            request.file(new MockMultipartFile("files", "report.pdf", "application/pdf", file));
        }
        MockHttpServletResponse response = who.browser().send(request);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    }

    /** An objection received at the counter by {@code counter}, decided UPHELD by {@code supervisor}, naming the stages. */
    private void upheld(SignedIn counter, SignedIn supervisor, String id, List<String> stages) throws Exception {
        counter.browser().csrf();
        MockHttpServletResponse received = counter.browser().send(multipart(PROCEDURES + "/" + id + "/objections")
                .file(new MockMultipartFile("objection", "", "application/json", json.writeValueAsString(Map.of("objectorName", "DEMO Neighbour Fouda",
                        "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-ID-7731", "interest", "NEIGHBOUR",
                        "statement", "The approved boundary takes the well my family has used for decades (synthetic)")).getBytes(StandardCharsets.UTF_8)))
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(received.getStatus()).as(received.getContentAsString()).isEqualTo(201);
        String objection = json.readTree(received.getContentAsString()).path("objection").asString("");
        MockHttpServletResponse decided = sendJson(supervisor.browser(), post(PROCEDURES + "/" + id + "/objections/" + objection + "/decision"),
                json.writeValueAsString(Map.of("outcome", "UPHELD", "grounds", "The well lies within the surveyed boundary (synthetic)", "reviewStages", stages)));
        assertThat(decided.getStatus()).as(decided.getContentAsString()).isEqualTo(200);
    }

    private MockHttpServletResponse reconsider(SignedIn supervisor, String id, long version, List<String> added, String justification) throws Exception {
        return sendJson(supervisor.browser(), post(PROCEDURES + "/" + id + "/reconsideration"), json.writeValueAsString(Map.of(
                "expectedVersion", version, "addedStages", added, "justification", justification)));
    }

    @Test
    void anObjectionUpheldAfterTheDecisionReopensTheCaseInANewRoundThatEndsInARegisteredTitle() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String parcel = parcel(cadastral, area(), 0);
        String id = immatriculationSubmitted(applicant, parcel, selfDeclaration("DEMO Owner Reconsidered"));
        paid(applicant, id);
        partiesReady(id, signedIn("INTAKE_OFFICER"), signedIn("INTAKE_OFFICER"));
        accepted(signedIn("INTAKE_OFFICER"), id);
        String today = LocalDate.now().toString();
        record(cadastral, id, "CADASTRAL_REVIEW", Map.of());
        record(cadastral, id, "FIELD_VISIT", Map.of("visitedOn", today, "members", List.of(Map.of("name", "DEMO Chief", "capacity", "Village chief"))));
        record(signedIn("COMMISSION_SECRETARY"), id, "COMMISSION_REPORT", Map.of("reportReference", "PV-DEMO-778", "reportDate", today,
                "signatories", List.of(Map.of("name", "DEMO Chair", "capacity", "Commission chair"))), DocumentFixtures.pdf("/Lang (pv)"));
        SignedIn publisher = signedIn("INTAKE_OFFICER");
        record(publisher, id, "PUBLICATION", Map.of());
        periodOver(id);
        approved(id);
        Conservator first = conservator();
        Conservator second = conservator();
        String reference = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString()).path("reference").asString("");

        // An objection received after the approval and upheld: the registration stops (ADR-042), and only a reconsideration reopens it.
        SignedIn objections = signedIn("SUPERVISOR");
        upheld(signedIn("INTAKE_OFFICER"), objections, id, List.of("CADASTRAL_REVIEW"));
        assertThat(code(propose(first.browser(), id, key()))).isEqualTo("OBJECTION_UPHELD_AFTER_DECISION");
        JsonNode asUpholder = json.readTree(objections.browser().send(get(PROCEDURES + "/" + id)).getContentAsString());
        assertThat(asUpholder.path("reconsiderProblem").asString("")).isEqualTo("OBJECTION_DECIDER_CANNOT_REOPEN");
        MockHttpServletResponse byUpholder = reconsider(objections, id, asUpholder.path("version").asLong(), List.of(),
                "The upheld objection was not before the decision authority (synthetic)");
        assertThat(byUpholder.getStatus()).isEqualTo(403);
        assertThat(code(byUpholder)).isEqualTo("OBJECTION_DECIDER_CANNOT_REOPEN");
        assertThat(reconsider(signedIn("INTAKE_OFFICER"), id, 0, List.of(), "An intake officer holds no such permission at all").getStatus())
                .isEqualTo(403);

        SignedIn supervisor = signedIn("SUPERVISOR");
        JsonNode before = json.readTree(supervisor.browser().send(get(PROCEDURES + "/" + id)).getContentAsString());
        assertThat(before.path("reconsiderProblem").isNull()).as(before.toString()).isTrue();
        long version = before.path("version").asLong();
        assertThat(code(reconsider(supervisor, id, version - 1, List.of(), "The upheld objection was not before the decision authority")))
                .isEqualTo("STALE_VERSION");
        assertThat(code(reconsider(supervisor, id, version, List.of(), "Too short"))).isEqualTo("JUSTIFICATION_REQUIRED");
        assertThat(code(reconsider(supervisor, id, version, List.of("DUTIES_EVIDENCE"), "The upheld objection was not before the decision authority")))
                .isEqualTo("REOPEN_STAGE_INVALID");
        String justification = "The neighbour's well was not before the decision authority (synthetic)";
        MockHttpServletResponse opened = reconsider(supervisor, id, version, List.of("FIELD_VISIT"), justification);
        assertThat(opened.getStatus()).as(opened.getContentAsString()).isEqualTo(200);
        JsonNode round1 = json.readTree(opened.getContentAsString());
        assertThat(round1.path("state").asString("")).isEqualTo("UNDER_REVIEW");
        assertThat(round1.path("round").asInt()).isEqualTo(1);
        assertThat(round1.path("reconsiderations").get(0).path("stages").toString()).isEqualTo("[\"CADASTRAL_REVIEW\",\"FIELD_VISIT\",\"PUBLICATION\"]");
        assertThat(round1.path("reconsiderations").get(0).path("addedStages").toString()).isEqualTo("[\"FIELD_VISIT\"]");
        assertThat(round1.path("reconsiderations").get(0).path("openedBySelf").asBoolean()).isTrue();
        assertThat(round1.path("reconsiderProblem").asString("")).isEqualTo("CASE_NOT_APPROVED");
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_event WHERE action = 'CASE_RECONSIDERATION_OPENED' AND entity_id = ?::uuid",
                String.class, id)).isEqualTo(justification);

        // The applicant reads that the case is reconsidered, which stages are carried out again; never why, nor the objection.
        MockHttpServletResponse mine = applicant.send(get(APPS + "/" + id));
        JsonNode asApplicant = json.readTree(mine.getContentAsString());
        ApplicantPayloadSchema.assertSafe(asApplicant, justification, "DEMO Neighbour Fouda");
        assertThat(asApplicant.path("state").asString("")).isEqualTo("UNDER_REVIEW");
        assertThat(asApplicant.path("decision").isNull()).as("no decision of the new round yet").isTrue();
        assertThat(asApplicant.path("reconsiderations").get(0).path("round").asInt()).isEqualTo(1);
        assertThat(asApplicant.path("reconsiderations").get(0).path("decided").asBoolean()).isFalse();
        assertThat(mine.getContentAsString()).doesNotContain(justification).doesNotContain("DEMO Neighbour Fouda");
        assertThat(code(propose(first.browser(), id, key()))).isEqualTo("CASE_NOT_APPROVED");

        // Round 1: the reopened stages recorded again, the notice published anew, a new review and decision.
        recordAgain(cadastral, id, "CADASTRAL_REVIEW", Map.of());
        recordAgain(cadastral, id, "FIELD_VISIT", Map.of("visitedOn", today, "members", List.of(Map.of("name", "DEMO Chief", "capacity", "Village chief"))));
        recordAgain(signedIn("COMMISSION_SECRETARY"), id, "COMMISSION_REPORT", Map.of("reportReference", "PV-DEMO-779", "reportDate", today,
                "signatories", List.of(Map.of("name", "DEMO Chair", "capacity", "Commission chair"))), DocumentFixtures.pdf("/Lang (pv2)"));
        recordAgain(publisher, id, "PUBLICATION", Map.of());
        periodOver(id);
        approved(id);
        JsonNode review = json.readTree(supervisor.browser().send(get("/api/v1/cases/" + id + "/review")).getContentAsString());
        assertThat(review.path("round").asInt()).isEqualTo(1);
        assertThat(review.path("decision").path("round").asInt()).isEqualTo(1);
        assertThat(review.path("earlierDecisions").size()).isEqualTo(1);
        assertThat(review.path("earlierDecisions").get(0).path("outcome").asString("")).isEqualTo("APPROVED");

        // Registration from the latest round's approval: two conservators, a title.
        assertThat(queued(first.browser(), reference).path("problems")).isEmpty();
        String inscription = json.readTree(propose(first.browser(), id, key()).getContentAsString()).path("id").asString("");
        assertThat(sign(first, inscription, "request-confirmation").getStatus()).isEqualTo(200);
        MockHttpServletResponse confirmed = sign(second, inscription, "confirm");
        assertThat(confirmed.getStatus()).as(confirmed.getContentAsString()).isEqualTo(200);
        JsonNode finalView = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        assertThat(finalView.path("reconsiderations").get(0).path("decided").asBoolean()).isTrue();
        assertThat(finalView.path("decision").path("outcome").asString("")).isEqualTo("APPROVED");
        assertThat(finalView.path("registration").path("titleNumber").asString("")).startsWith("DEMO-TF-");
        // D42: a registered title is never reopened through its case.
        assertThat(json.readTree(supervisor.browser().send(get(PROCEDURES + "/" + id)).getContentAsString()).path("reconsiderProblem").asString(""))
                .isEqualTo("CASE_REGISTERED");
    }

    /** Runs statements as the schema owner, as a database administrator could. */
    private static void superuser(String sql) throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection(); java.sql.Statement st = su.createStatement()) {
            st.execute(sql);
            su.commit();
        }
    }

    @Test
    void aProposalBecomesStaleWhenAnOwnersIdentityChangesBetweenItsSignaturesAndIsNeverSignedAsItWas() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String parcel = parcel(cadastral, area(), 0);
        String id = immatriculationSubmitted(applicant, parcel, selfDeclaration("DEMO Owner Stale"));
        paid(applicant, id);
        SignedIn recorder = signedIn("INTAKE_OFFICER");
        SignedIn checker = signedIn("INTAKE_OFFICER");
        partiesReady(id, recorder, checker);
        accepted(signedIn("INTAKE_OFFICER"), id);
        String today = LocalDate.now().toString();
        record(cadastral, id, "CADASTRAL_REVIEW", Map.of());
        record(cadastral, id, "FIELD_VISIT", Map.of("visitedOn", today, "members", List.of(Map.of("name", "DEMO Chief", "capacity", "Village chief"))));
        record(signedIn("COMMISSION_SECRETARY"), id, "COMMISSION_REPORT", Map.of("reportReference", "PV-DEMO-777", "reportDate", today,
                "signatories", List.of(Map.of("name", "DEMO Chair", "capacity", "Commission chair"))), DocumentFixtures.pdf("/Lang (pv)"));
        record(signedIn("INTAKE_OFFICER"), id, "PUBLICATION", Map.of());
        periodOver(id);
        approved(id);
        Conservator first = conservator();
        Conservator second = conservator();
        String inscription = json.readTree(propose(first.browser(), id, key()).getContentAsString()).path("id").asString("");
        assertThat(sign(first, inscription, "request-confirmation").getStatus()).isEqualTo(200);

        // After the first signature, the owner's identity is corrected and checked again.
        JsonNode person = parties(recorder, id).path("persons").get(0);
        JsonNode latest = person.path("identities").get(0);
        String file = parties(recorder, id).path("files").get(0).path("id").asString("");
        MockHttpServletResponse corrected = sendJson(recorder.browser(), post(PROCEDURES + "/" + id + "/parties/persons/" + person.path("id").asString("")
                + "/identity"), json.writeValueAsString(Map.of("fullName", "DEMO Owner Stale Corrected", "identityKind", "NATIONAL_ID",
                "identityNumber", latest.path("identityNumber").asString(""), "phone", latest.path("phone").asString(""), "sourceFile", file,
                "supersedes", latest.path("id").asString(""), "correctionReason", "The name was misspelt on the first transcription")));
        assertThat(corrected.getStatus()).as(corrected.getContentAsString()).isEqualTo(200);
        check(checker, id, "IDENTITY", parties(checker, id).path("persons").get(0).path("identities").get(0).path("id").asString(""));

        MockHttpServletResponse stale = sign(second, inscription, "confirm");
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("STALE_PROPOSAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM land_title WHERE application_id = ?::uuid", Integer.class, id)).isZero();
        // A stale proposal is withdrawn; a new one waits for the reconsideration (ADR-043 step 5b): the change is after the decision.
        MockHttpServletResponse withdrawn = sendJson(first.browser(), post(REGISTER + "/inscriptions/" + inscription + "/withdraw"),
                json.writeValueAsString(Map.of("reason", "Stale: an owner's identity was corrected after the decision")));
        assertThat(withdrawn.getStatus()).as(withdrawn.getContentAsString()).isEqualTo(200);
        assertThat(code(propose(first.browser(), id, key()))).isEqualTo("PARTIES_CHANGED_SINCE_DECISION");
    }

    // --- Procedure B, the mutation totale (ADR-042 step 6, V37) ----------------------------------------------------

    /** A synthetic title on {@code parcel}, held by a seller with the identity given, registered by {@code keeper}; returns its id. */
    private String syntheticTitle(Conservator keeper, String parcel, String sellerName, String sellerNumber) throws Exception {
        MockHttpServletResponse party = sendJson(keeper.browser(), post(REGISTER + "/parties"), body("officeCode", "DEMOIT", "kind", "NATURAL_PERSON",
                "fullName", sellerName, "identityKind", "NATIONAL_ID", "identityNumber", sellerNumber));
        assertThat(party.getStatus()).as(party.getContentAsString()).isEqualTo(201);
        String version = jdbc.queryForObject("""
                SELECT v.id::text FROM parcel_version v JOIN parcel p ON p.id = v.parcel_id WHERE p.reference = ? AND v.version_no = 1""", String.class, parcel);
        keeper.browser().csrf();
        MockHttpServletResponse proposed = keeper.browser().send(json(post(REGISTER + "/inscriptions").header("Idempotency-Key", key()), body(
                "officeCode", "DEMOIT", "kind", "CREATION", "expectedVersion", 0, "authorityType", "SYNTHETIC_DEMO", "authorityReference", "DEMO-AUTH",
                "reason", "A synthetic title to be sold", "evidenceReferences", List.of("DEMO-EVIDENCE-1"), "areaM2", 10000, "parcelVersionId", version,
                "holders", List.of(Map.of("party", json.readTree(party.getContentAsString()).path("id").asString(""), "interest", "OWNERSHIP",
                        "shareNum", 1, "shareDen", 1)))));
        assertThat(proposed.getStatus()).as(proposed.getContentAsString()).isEqualTo(201);
        MockHttpServletResponse registered = sign(keeper, json.readTree(proposed.getContentAsString()).path("id").asString(""), "register");
        assertThat(registered.getStatus()).as(registered.getContentAsString()).isEqualTo(200);
        return json.readTree(registered.getContentAsString()).path("titleId").asString("");
    }

    /** The buyer applies for themselves as the sole transferee; the seller is the transferor (D37). */
    private static Map<String, Object> saleDeclaration(String sellerName, String sellerNumber) {
        return Map.of("capacity", "SELF",
                "persons", List.of(
                        Map.of("ref", "p1", "kind", "NATURAL_PERSON", "roles", List.of("APPLICANT", "TRANSFEREE"), "fullName", "DEMO Buyer Nkodo",
                                "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-ID-BUYER-0601", "phone", "+237 690000601"),
                        Map.of("ref", "p2", "kind", "NATURAL_PERSON", "roles", List.of("TRANSFEROR"), "fullName", sellerName,
                                "identityKind", "NATIONAL_ID", "identityNumber", sellerNumber)),
                "shares", List.of(Map.of("person", "p1", "interest", "OWNERSHIP", "shareNum", 1, "shareDen", 1)));
    }

    private MockHttpServletResponse saveMutation(Browser applicant, String id, long version, String parcel, String titleNumber,
                                                 Map<String, Object> declaration) throws Exception {
        return sendJson(applicant, put(APPS + "/" + id), json.writeValueAsString(Map.of("expectedVersion", version, "parcelReference", parcel,
                "data", Map.of("titleNumber", titleNumber, "statement", "A sale received by DEMO Notary (synthetic)"), "declaration", declaration)));
    }

    private MockHttpServletResponse submit(Browser applicant, String id, long version) throws Exception {
        return sendJson(applicant, post(APPS + "/" + id + "/submission").header("Idempotency-Key", key()), "{\"expectedVersion\":" + version + "}");
    }

    /** The notary deposits the sale through their account: the deed and the duty receipt, naming the case by its reference. */
    private MockHttpServletResponse notaryDeposit(SignedIn notary, String title, String caseReference) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/professional/deposits");
        request.file(new MockMultipartFile("files", "deed.pdf", "application/pdf", DocumentFixtures.pdf("/Title (deed " + UUID.randomUUID() + ")")));
        request.param("categories", "DEED");
        request.file(new MockMultipartFile("files", "receipt.pdf", "application/pdf", DocumentFixtures.pdf("/Title (receipt " + UUID.randomUUID() + ")")));
        request.param("categories", "TAX_OR_DUTY");
        request.param("titleId", title).param("kind", "SALE").param("depositorKind", "NATURAL_PERSON").param("depositorName", "DEMO Buyer Nkodo")
                .param("depositorIdentityKind", "NATIONAL_ID").param("depositorIdentityNumber", "DEMO-ID-BUYER-0601").param("caseReference", caseReference)
                .header("Idempotency-Key", key());
        notary.browser().csrf();
        return notary.browser().send(request);
    }

    private MockHttpServletResponse proposeTransfer(Browser who, String id) throws Exception {
        who.csrf();
        return who.send(json(post(REGISTER + "/cases/" + id + "/transfer").header("Idempotency-Key", key()), json.writeValueAsString(Map.of(
                "reason", "Approved sale, title verified and duties evidenced (synthetic)"))));
    }

    @Test
    void aSaleDepositedByTheNotaryForTheCaseIsRegisteredAsATransferSignedTwiceOnTheTitleItNames() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Conservator keeper = conservator();
        String parcel = parcel(cadastral, area(), 0);
        String seller = "DEMO Seller Abega";
        String sellerNumber = "DEMO-ID-SELLER-" + UUID.randomUUID().toString().substring(0, 8);
        String title = syntheticTitle(keeper, parcel, seller, sellerNumber);
        String number = superuserText("SELECT number FROM snigf.land_title WHERE id = '" + UUID.fromString(title) + "'");

        // Opening: the case names its title; one unknown at the office is refused at submission (V37).
        Browser applicant = applicant();
        String id = json.readTree(sendJson(applicant, post(APPS).header("Idempotency-Key", key()),
                "{\"typeCode\":\"DEMO_MUTATION\",\"data\":{}}").getContentAsString()).path("id").asString("");
        assertThat(saveMutation(applicant, id, 0, parcel, "DEMO-TF-DEMOIT-999999-Z", saleDeclaration(seller, sellerNumber)).getStatus()).isEqualTo(200);
        applicant.csrf();
        assertThat(applicant.send(multipart(APPS + "/" + id + "/documents/DEMO_IDENTITY")
                .file(new MockMultipartFile("file", "id.pdf", "application/pdf", DocumentFixtures.pdf("/Lang (buyer id)")))
                .header("Idempotency-Key", key())).getStatus()).isEqualTo(201);
        MockHttpServletResponse unknown = submit(applicant, id, 1);
        assertThat(unknown.getStatus()).as(unknown.getContentAsString()).isEqualTo(422);
        assertThat(unknown.getContentAsString()).contains("TITLE_NOT_FOUND");
        assertThat(saveMutation(applicant, id, 1, parcel, number, saleDeclaration(seller, sellerNumber)).getStatus()).isEqualTo(200);
        MockHttpServletResponse submitted = submit(applicant, id, 2);
        assertThat(submitted.getStatus()).as(submitted.getContentAsString()).isEqualTo(200);
        assertThat(superuserText("SELECT title_id::text FROM snigf.application WHERE id = '" + UUID.fromString(id) + "'")).isEqualTo(title);
        paid(applicant, id);
        partiesReady(id, signedIn("INTAKE_OFFICER"), signedIn("INTAKE_OFFICER"));
        accepted(signedIn("INTAKE_OFFICER"), id);
        String reference = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString()).path("reference").asString("");

        // The deposit: through the notary's account, naming the case; another title or an unknown case is refused.
        SignedIn notary = activeProfessional("NOTARY", "DEMOIT");
        assertThat(code(notaryDeposit(notary, title, "DEMO-APP-2026-999999-X"))).isEqualTo("CASE_NOT_FOUND");
        MockHttpServletResponse deposited = notaryDeposit(notary, title, reference);
        assertThat(deposited.getStatus()).as(deposited.getContentAsString()).isEqualTo(201);
        String deposit = json.readTree(deposited.getContentAsString()).path("id").asString("");
        assertThat(code(notaryDeposit(notary, title, reference))).as("one open deposit per case").isEqualTo("CASE_DEPOSIT_OPEN");
        String receipt = superuserText("SELECT id::text FROM snigf.deposit_document WHERE transaction_id = '" + UUID.fromString(deposit)
                + "' AND category = 'TAX_OR_DUTY'");

        // The stages, by a conservator: the title verified against the transferor (D45), the duty receipt evidenced (D24).
        SignedIn verifier = signedIn("CONSERVATOR");
        record(verifier, id, "TITLE_VERIFICATION", Map.of());
        record(verifier, id, "DUTIES_EVIDENCE", Map.of("receiptDocument", receipt, "receiptReference", "DEMO-QUITTANCE-0601"));
        approved(id);

        // "Approved, to register" lists the transfer and its title.
        Conservator first = conservator();
        Conservator second = conservator();
        JsonNode entry = queued(first.browser(), reference);
        assertThat(entry).as("the approved sale waits for its registration").isNotNull();
        assertThat(entry.path("registers").asString("")).isEqualTo("TRANSFER");
        assertThat(entry.path("titleNumber").asString("")).isEqualTo(number);
        assertThat(entry.path("problems")).isEmpty();
        assertThat(entry.path("owners").asString("")).isEqualTo("DEMO Buyer Nkodo (1/1)");
        JsonNode evidence = json.readTree(first.browser().send(get(REGISTER + "/cases/" + id + "/evidence")).getContentAsString());
        assertThat(evidence.path("transferors").findValuesAsString("identityNumber")).containsExactly(sellerNumber);
        assertThat(evidence.path("deposit").path("id").asString("")).isEqualTo(deposit);

        // Never proposed by whoever recorded its stages; never through ADR-040's plain path; never a CREATION.
        MockHttpServletResponse byVerifier = proposeTransfer(verifier.browser(), id);
        assertThat(byVerifier.getStatus()).isEqualTo(403);
        assertThat(code(byVerifier)).isEqualTo("CASE_HANDLER_CANNOT_REGISTER");
        assertThat(code(propose(first.browser(), id, key()))).isEqualTo("PROCEDURE_NOT_CREATION");

        // The proposal, then two signatures (D46).
        MockHttpServletResponse proposed = proposeTransfer(first.browser(), id);
        assertThat(proposed.getStatus()).as(proposed.getContentAsString()).isEqualTo(201);
        String inscription = json.readTree(proposed.getContentAsString()).path("id").asString("");
        assertThat(code(proposeTransfer(second.browser(), id))).isEqualTo("TRANSFER_ALREADY_PROPOSED");
        JsonNode proposal = json.readTree(first.browser().send(get(REGISTER + "/inscriptions/" + inscription)).getContentAsString());
        assertThat(proposal.path("kind").asString("")).isEqualTo("TRANSFER");
        assertThat(proposal.path("applicationId").asString("")).isEqualTo(id);
        assertThat(proposal.path("holders").findValuesAsString("partyName")).containsExactly("DEMO Buyer Nkodo");
        assertThat(code(sign(first, inscription, "register"))).isEqualTo("DUAL_CONTROL_REQUIRED");
        assertThat(sign(first, inscription, "request-confirmation").getStatus()).isEqualTo(200);
        MockHttpServletResponse registered = sign(second, inscription, "confirm");
        assertThat(registered.getStatus()).as(registered.getContentAsString()).isEqualTo(200);

        // The title keeps its number; the buyer holds it, the seller stays in its history; its signatures verify.
        JsonNode view = json.readTree(first.browser().send(get(REGISTER + "/titles/" + title)).getContentAsString());
        assertThat(view.path("title").path("number").asString("")).isEqualTo(number);
        assertThat(view.path("title").path("holders").findValuesAsString("partyName")).containsExactly("DEMO Buyer Nkodo");
        assertThat(view.toString()).contains(seller);
        JsonNode verified = json.readTree(first.browser().send(get(REGISTER + "/titles/" + title + "/verification")).getContentAsString());
        assertThat(verified.path("intact").asBoolean()).as(verified.toString()).isTrue();
        assertThat(superuserText("SELECT count(*) FROM snigf.land_title WHERE application_id = '" + UUID.fromString(id) + "'")).isEqualTo("0");

        // The applicant reads that the transfer is registered, on which title and when; nothing else.
        JsonNode mine = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        assertThat(mine.path("state").asString("")).isEqualTo("APPROVED");
        assertThat(mine.path("registration").path("kind").asString("")).isEqualTo("TRANSFER");
        assertThat(mine.path("registration").path("titleNumber").asString("")).isEqualTo(number);
        ApplicantPayloadSchema.assertSafe(mine, "Approved sale, title verified", "DEMO-QUITTANCE-0601");
        assertThat(code(proposeTransfer(first.browser(), id))).isEqualTo("CASE_ALREADY_REGISTERED");
        assertThat(queued(first.browser(), reference)).isNull();
    }

    private static String superuserText(String sql) throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection(); java.sql.Statement st = su.createStatement();
             var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void aCaseOfTheGenericTypeNeverBecomesATitle() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String id = underReview(applicant, parcel(cadastral, area(), 0));
        approved(id);
        Conservator conservator = conservator();
        MockHttpServletResponse refused = propose(conservator.browser(), id, key());
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(code(refused)).isEqualTo("PROCEDURE_NOT_CREATION");
        assertThat(json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString()).path("registration").isNull()).isTrue();
    }
}
