// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.ApplicantPayloadSchema;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.snigf.mvp.document.DocumentFixtures;
import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Procedure A's stages through the real endpoints (ADR-042 step 3): each recorded by the officer its permission names,
 * corrected by new records that keep the old ones, the commission's report by its secretary with its file, the gate
 * holding an approval recommendation while a stage is missing, a refusal never gated, the applicant seeing statuses
 * and dates only, every write and refusal audited. Step 4: the publication opening a public notice, objections received
 * at the counter and decided by someone else, an upheld one sending its stages back, the case held until every
 * objection is decided and the period is over.
 */
class ProcedureIT extends CaseFlowIntegrationTest {

    private static final String NOTICES = "/api/v1/public/notices";

    @Autowired
    DocumentProperties storage;

        private MockHttpServletResponse record(SignedIn who, String id, String stage, Map<String, Object> record, byte[]... files) throws Exception {
        who.browser().csrf();
        MockMultipartHttpServletRequestBuilder request = multipart(PROCEDURES + "/" + id + "/stages/" + stage);
        request.file(new MockMultipartFile("record", "", "application/json", json.writeValueAsString(record).getBytes(StandardCharsets.UTF_8)));
        int n = 0;
        for (byte[] file : files) {
            request.file(new MockMultipartFile("files", "report-" + (n++) + ".pdf", "application/pdf", file));
        }
        return who.browser().send(request);
    }

    private static Map<String, Object> rec(String outcome, String finding, Map<String, Object> details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", outcome);
        m.put("finding", finding);
        m.put("details", details);
        return m;
    }

    private JsonNode view(SignedIn who, String id) throws Exception {
        MockHttpServletResponse response = who.browser().send(get(PROCEDURES + "/" + id));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private static JsonNode stage(JsonNode view, String code) {
        for (JsonNode s : view.path("stages")) {
            if (s.path("code").asString("").equals(code)) {
                return s;
            }
        }
        throw new AssertionError("no stage " + code);
    }

    private MockHttpServletResponse object(SignedIn who, String id, String key, Map<String, Object> objection, byte[]... files) throws Exception {
        who.browser().csrf();
        MockMultipartHttpServletRequestBuilder request = multipart(PROCEDURES + "/" + id + "/objections");
        request.file(new MockMultipartFile("objection", "", "application/json", json.writeValueAsString(objection).getBytes(StandardCharsets.UTF_8)));
        int n = 0;
        for (byte[] file : files) {
            request.file(new MockMultipartFile("files", "objection-" + (n++) + ".pdf", "application/pdf", file));
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return who.browser().send(request);
    }

    private static Map<String, Object> objection(String name, String interest, String statement) {
        return Map.of("objectorName", name, "identityKind", "NATIONAL_ID", "identityNumber", "DEMO-ID-7731", "interest", interest,
                "statement", statement);
    }

    private MockHttpServletResponse decide(SignedIn who, String id, String objection, String outcome, String grounds, List<String> stages)
            throws Exception {
        return decide(who, id, objection, outcome, grounds, stages, null);
    }

    private MockHttpServletResponse decide(SignedIn who, String id, String objection, String outcome, String grounds, List<String> stages,
                                           String instructions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome);
        body.put("grounds", grounds);
        body.put("reviewStages", stages);
        if (instructions != null) {
            body.put("applicantInstructions", instructions);
        }
        return sendJson(who.browser(), post(PROCEDURES + "/" + id + "/objections/" + objection + "/decision"), json.writeValueAsString(body));
    }

    /** Moves every notice of the case into the past, as the schema owner (a notice is append-only for everyone else). */
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

    private static String superuserText(String sql, String param) throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.PreparedStatement q = su.prepareStatement(sql)) {
            q.setString(1, param);
            try (java.sql.ResultSet rs = q.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Path stored(String storageKey) {
        return storage.directory().resolve(storageKey.substring(0, 2)).resolve(storageKey.substring(2, 4)).resolve(storageKey);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private int audited(String action, String id) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND entity_id = ?::uuid", Integer.class, action, id);
    }

    @Test
    void stagesAreRecordedByTheirOfficersCorrectedByNewRecordsAndHoldTheApproval() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String parcel = parcel(cadastral, area(), 0);
        String id = immatriculationUnderReview(applicant, parcel);
        SignedIn secretary = signedIn("COMMISSION_SECRETARY");
        SignedIn intake = signedIn("INTAKE_OFFICER");
        String today = LocalDate.now().toString();
        Map<String, Object> visit = Map.of("visitedOn", today, "members", List.of(Map.of("name", "DEMO Chief", "capacity", "Village chief")));
        Map<String, Object> report = Map.of("reportReference", "PV-DEMO-001", "reportDate", today,
                "signatories", List.of(Map.of("name", "DEMO Chair", "capacity", "Commission chair")));

        // Before anything: four stages, none recorded; the gate lists them.
        JsonNode before = view(cadastral, id);
        assertThat(before.path("procedure").asString("")).isEqualTo("A");
        assertThat(before.path("stages").findValuesAsString("code")).containsExactly("CADASTRAL_REVIEW", "FIELD_VISIT", "COMMISSION_REPORT", "PUBLICATION");
        assertThat(stage(before, "CADASTRAL_REVIEW").path("mayRecord").asBoolean()).isTrue();
        assertThat(stage(before, "COMMISSION_REPORT").path("mayRecord").asBoolean()).as("not the cadastral officer's (D26)").isFalse();
        assertThat(before.path("gate").toString()).contains("STAGE_MISSING:CADASTRAL_REVIEW", "STAGE_MISSING:PUBLICATION");

        // The cadastral review: the database records the parcel version.
        MockHttpServletResponse reviewed = record(cadastral, id, "CADASTRAL_REVIEW", rec("COMPLETED", "The parcel matches the cadastre", Map.of()));
        assertThat(reviewed.getStatus()).as(reviewed.getContentAsString()).isEqualTo(200);
        assertThat(stage(json.readTree(reviewed.getContentAsString()), "CADASTRAL_REVIEW").path("records").get(0).path("details")
                .path("parcelReference").asString("")).isEqualTo(parcel);
        assertThat(code(record(cadastral, id, "CADASTRAL_REVIEW", rec("COMPLETED", "The parcel matches again", Map.of()))))
                .as("a retry adds nothing").isEqualTo("STAGE_ALREADY_RECORDED");

        // The report waits for a completed visit; the visit is first not satisfied, then corrected.
        assertThat(code(record(secretary, id, "COMMISSION_REPORT", rec("COMPLETED", "The commission agrees", report), DocumentFixtures.pdf("/Lang (pv)"))))
                .isEqualTo("FIELD_VISIT_REQUIRED");
        JsonNode first = json.readTree(record(cadastral, id, "FIELD_VISIT", rec("NOT_SATISFIED", "The boundary markers were missing", visit))
                .getContentAsString());
        String visitId = stage(first, "FIELD_VISIT").path("records").get(0).path("id").asString("");
        Map<String, Object> correction = rec("COMPLETED", "Markers placed and checked", visit);
        assertThat(code(record(cadastral, id, "FIELD_VISIT", correction))).as("a correction names what it replaces").isEqualTo("STAGE_ALREADY_RECORDED");
        correction.put("supersedes", visitId);
        assertThat(code(record(cadastral, id, "FIELD_VISIT", correction))).as("... and says why").isEqualTo("CORRECTION_REASON_REQUIRED");
        correction.put("correctionReason", "The markers were placed again the same day");
        MockHttpServletResponse corrected = record(cadastral, id, "FIELD_VISIT", correction);
        assertThat(corrected.getStatus()).as(corrected.getContentAsString()).isEqualTo(200);
        JsonNode chain = stage(json.readTree(corrected.getContentAsString()), "FIELD_VISIT").path("records");
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).path("supersedes").asString("")).as("newest first, naming the one it replaces").isEqualTo(visitId);
        assertThat(chain.get(1).path("outcome").asString("")).as("the replaced record is kept as it was").isEqualTo("NOT_SATISFIED");

        // D26: the cadastral officer does not hold the report's permission; the secretary records it, with its file.
        assertThat(record(cadastral, id, "COMMISSION_REPORT", rec("COMPLETED", "The commission agrees", report),
                DocumentFixtures.pdf("/Lang (pv)")).getStatus()).isEqualTo(403);
        MockHttpServletResponse reported = record(secretary, id, "COMMISSION_REPORT", rec("COMPLETED", "The commission agrees on the limits", report),
                DocumentFixtures.pdf("/Lang (pv)"));
        assertThat(reported.getStatus()).as(reported.getContentAsString()).isEqualTo(200);
        JsonNode file = stage(json.readTree(reported.getContentAsString()), "COMMISSION_REPORT").path("records").get(0).path("files").get(0);
        MockHttpServletResponse download = cadastral.browser().send(get(PROCEDURES + "/" + id + "/files/" + file.path("id").asString("")));
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(download.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        // Publication (V33) is available once the stages before it hold; its own journey is the next test.
        assertThat(stage(view(intake, id), "PUBLICATION").path("mayRecord").asBoolean()).isTrue();

        // The applicant: statuses and dates only, never the finding, the officer or the commission.
        JsonNode mine = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        ApplicantPayloadSchema.assertSafe(mine, "Markers placed and checked", "DEMO Chief", "PV-DEMO-001");
        assertThat(mine.path("stages").findValuesAsString("status")).containsExactly("COMPLETED", "COMPLETED", "COMPLETED", "NOT_STARTED");
        assertThat(applicant.send(get(PROCEDURES + "/" + id)).getStatus()).as("the applicant reads no stage record").isEqualTo(403);

        // The gate: no approval recommended while publication is missing; a refusal stays possible.
        SignedIn reviewer = signedIn("REVIEWER");
        assertThat(act(reviewer, id, "review-claim", "{}").getStatus()).isEqualTo(200);
        JsonNode review = json.readTree(reviewer.browser().send(get(REVIEWS + "/" + id)).getContentAsString());
        assertThat(review.path("procedureGate").toString()).contains("STAGE_MISSING:PUBLICATION");
        MockHttpServletResponse approval = act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id),
                "recommendation", "RECOMMEND_APPROVAL", "finding", "Everything is consistent so far"));
        assertThat(approval.getStatus()).isEqualTo(409);
        assertThat(code(approval)).isEqualTo("PROCEDURE_INCOMPLETE");
        assertThat(code(record(cadastral, id, "FIELD_VISIT", rec("COMPLETED", "Another check", visit))))
                .as("the reviewer's claim does not open the stages to anyone else").isEqualTo("STAGE_ALREADY_RECORDED");
        MockHttpServletResponse refusal = act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id),
                "recommendation", "RECOMMEND_REFUSAL", "finding", "The occupation is not established"));
        assertThat(refusal.getStatus()).as(refusal.getContentAsString()).isEqualTo(200);
        assertThat(code(record(cadastral, id, "CADASTRAL_REVIEW", rec("COMPLETED", "Late check", Map.of())))).isEqualTo("CASE_NOT_UNDER_REVIEW");

        // Audited: three stages recorded, one correction, the file read, each refusal on its own.
        assertThat(audited("PROCEDURE_STAGE_RECORDED", id)).isEqualTo(3);
        assertThat(audited("PROCEDURE_STAGE_CORRECTED", id)).isEqualTo(1);
        assertThat(audited("PROCEDURE_STAGE_FILE_DOWNLOADED", id)).isEqualTo(1);
        List<String> refused = new ArrayList<>(jdbc.queryForList("SELECT reason FROM audit_event WHERE action = 'PROCEDURE_REFUSED'"
                + " AND entity_id = ?::uuid ORDER BY chain_pos", String.class, id));
        assertThat(refused).contains("STAGE_ALREADY_RECORDED", "FIELD_VISIT_REQUIRED", "CORRECTION_REASON_REQUIRED", "CASE_NOT_UNDER_REVIEW");
    }

    /**
     * Step 3 verification: a stage file's SHA-256 is computed by the server from the bytes received and recorded; the
     * original is kept byte for byte and served back unchanged, as an attachment with its digest; only the case's readers
     * download it, never the applicant, another office or through another case; every download is audited, and a file
     * altered in storage is refused and the refusal audited, never served.
     */
    @Test
    void stageEvidenceKeepsItsHashItsBytesItsReadersAndItsAudit() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String id = immatriculationUnderReview(applicant, parcel(cadastral, area(), 0));
        byte[] original = DocumentFixtures.pdf("/Lang (cadastral evidence)");
        MockHttpServletResponse reviewed = record(cadastral, id, "CADASTRAL_REVIEW", rec("COMPLETED", "The parcel matches the cadastre", Map.of()),
                original);
        assertThat(reviewed.getStatus()).as(reviewed.getContentAsString()).isEqualTo(200);
        JsonNode file = stage(json.readTree(reviewed.getContentAsString()), "CADASTRAL_REVIEW").path("records").get(0).path("files").get(0);
        String fileId = file.path("id").asString("");

        // The hash is the server's, of the bytes received, and it is what the database recorded.
        assertThat(file.path("sha256").asString("")).isEqualTo(sha256(original));
        assertThat(superuserText("SELECT sha256 FROM snigf.procedure_stage_file WHERE id = ?::uuid", fileId)).isEqualTo(sha256(original));
        String key = superuserText("SELECT storage_key FROM snigf.procedure_stage_file WHERE id = ?::uuid", fileId);
        assertThat(Files.readAllBytes(stored(key))).as("the original is kept byte for byte").isEqualTo(original);
        assertThat(Files.isWritable(stored(key))).as("... read-only in storage").isFalse();

        // Read back unchanged by another reader of the case, as an attachment with its digest; the read is audited.
        SignedIn intake = signedIn("INTAKE_OFFICER");
        MockHttpServletResponse download = intake.browser().send(get(PROCEDURES + "/" + id + "/files/" + fileId));
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentAsByteArray()).isEqualTo(original);
        assertThat(download.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(download.getHeader("Repr-Digest")).isEqualTo("sha-256=:" + java.util.Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(original)) + ":");
        assertThat(download.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(audited("PROCEDURE_STAGE_FILE_DOWNLOADED", id)).isEqualTo(1);

        // Never the applicant, another office, or through another case.
        assertThat(applicant.send(get(PROCEDURES + "/" + id + "/files/" + fileId)).getStatus()).isEqualTo(403);
        SignedIn elsewhere = signedIn("CADASTRAL_OFFICER", "DEMOIT2");
        assertThat(elsewhere.browser().send(get(PROCEDURES + "/" + id + "/files/" + fileId)).getStatus()).isEqualTo(403);
        String other = underReview(applicant(), parcel(cadastral, area(), 0));
        MockHttpServletResponse crossCase = intake.browser().send(get(PROCEDURES + "/" + other + "/files/" + fileId));
        assertThat(crossCase.getStatus()).isEqualTo(404);
        assertThat(code(crossCase)).isEqualTo("FILE_NOT_FOUND");

        // Altered in storage (by someone with the host's rights): refused, never served, the refusal audited on its own.
        stored(key).toFile().setWritable(true);
        Files.write(stored(key), DocumentFixtures.pdf("/Lang (altered)"));
        MockHttpServletResponse altered = intake.browser().send(get(PROCEDURES + "/" + id + "/files/" + fileId));
        assertThat(altered.getStatus()).isEqualTo(409);
        assertThat(code(altered)).isEqualTo("FILE_INTEGRITY_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'PROCEDURE_REFUSED' AND entity_id = ?::uuid"
                + " AND reason = 'FILE_INTEGRITY_FAILED'", Integer.class, id)).isEqualTo(1);
        assertThat(audited("PROCEDURE_STAGE_FILE_DOWNLOADED", id)).as("nothing more was served").isEqualTo(1);
        Files.write(stored(key), original);
        stored(key).toFile().setWritable(false);
    }

    /**
     * Step 4: the publication opens a public notice with the setting's period; an objection received at the counter is
     * recorded once (a retry gives it back), holds every recommendation, is decided by someone other than its recorder
     * with grounds, and when upheld sends the stage it names back without refusing the case; once the stages are recorded
     * again, the notice published anew and its period over, a late objection is still received, and decided, and only
     * then may approval be recommended. The objector's identity reaches neither the reviewer, the applicant nor the audit.
     */
    @Test
    void publicationOpensANoticeAndObjectionsHoldTheCaseUntilDecided() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String parcel = parcel(cadastral, area(), 0);
        String id = immatriculationUnderReview(applicant, parcel);
        SignedIn secretary = signedIn("COMMISSION_SECRETARY");
        SignedIn intake = signedIn("INTAKE_OFFICER");
        SignedIn counter = signedIn("INTAKE_OFFICER");
        addRole(counter.id(), "SUPERVISOR");
        SignedIn supervisor = signedIn("SUPERVISOR");
        String today = LocalDate.now().toString();
        Map<String, Object> visit = Map.of("visitedOn", today, "members", List.of(Map.of("name", "DEMO Chief", "capacity", "Village chief")));
        Map<String, Object> report = Map.of("reportReference", "PV-DEMO-002", "reportDate", today,
                "signatories", List.of(Map.of("name", "DEMO Chair", "capacity", "Commission chair")));
        assertThat(record(cadastral, id, "CADASTRAL_REVIEW", rec("COMPLETED", "The parcel matches the cadastre", Map.of())).getStatus()).isEqualTo(200);
        JsonNode visited = json.readTree(record(cadastral, id, "FIELD_VISIT", rec("COMPLETED", "Markers in place", visit)).getContentAsString());
        String visitId = stage(visited, "FIELD_VISIT").path("records").get(0).path("id").asString("");
        JsonNode reported = json.readTree(record(secretary, id, "COMMISSION_REPORT", rec("COMPLETED", "The commission agrees", report),
                DocumentFixtures.pdf("/Lang (pv)")).getContentAsString());
        String reportId = stage(reported, "COMMISSION_REPORT").path("records").get(0).path("id").asString("");

        // The publication: the period is the setting's, never the officer's.
        assertThat(code(record(intake, id, "PUBLICATION", rec("COMPLETED", "Posted on the office board", Map.of("objectionPeriod", "PT1M")))))
                .isEqualTo("DETAILS_INVALID");
        MockHttpServletResponse published = record(intake, id, "PUBLICATION", rec("COMPLETED", "Posted on the office board and at the chiefdom", Map.of()));
        assertThat(published.getStatus()).as(published.getContentAsString()).isEqualTo(200);
        JsonNode afterPublication = json.readTree(published.getContentAsString());
        JsonNode notice = afterPublication.path("notices").get(0);
        String noticeNumber = notice.path("number").asString("");
        assertThat(noticeNumber).matches("DEMO-PUB-\\d{4}-\\d{6}");
        assertThat(notice.path("current").asBoolean()).isTrue();
        assertThat(notice.path("periodOpen").asBoolean()).isTrue();
        assertThat(notice.path("parcelReference").asString("")).isEqualTo(parcel);
        assertThat(stage(afterPublication, "PUBLICATION").path("status").asString("")).isEqualTo("IN_PROGRESS");
        assertThat(afterPublication.path("gate").toString()).contains("OBJECTION_PERIOD_OPEN");
        assertThat(audited("PROCEDURE_NOTICE_PUBLISHED", id)).isEqualTo(1);

        // The public notice: anyone reads it, and it says only what notification needs.
        MockHttpServletResponse listed = new Browser().send(get(NOTICES));
        assertThat(listed.getStatus()).isEqualTo(200);
        JsonNode publicList = json.readTree(listed.getContentAsString());
        assertThat(publicList.path("demo").asBoolean()).isTrue();
        JsonNode ours = null;
        for (JsonNode n : publicList.path("notices")) {
            if (n.path("number").asString("").equals(noticeNumber)) {
                ours = n;
            }
        }
        assertThat(ours).as("the open notice is listed").isNotNull();
        List<String> fields = new ArrayList<>();
        ours.propertyNames().forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("number", "procedure", "parcelReference", "locality", "office", "opensAt", "closesAt");
        String reference = afterPublication.path("reference").asString("");
        assertThat(listed.getContentAsString()).doesNotContain(reference, id);

        // An objection at the counter: once, whatever the retries; never by the applicant.
        Map<String, Object> neighbour = objection("DEMO Neighbour Abena", "NEIGHBOUR", "The eastern boundary crosses my field (synthetic)");
        assertThat(code(object(counter, id, null, neighbour))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        applicant.csrf();
        assertThat(applicant.send(multipart(PROCEDURES + "/" + id + "/objections").file(new MockMultipartFile("objection", "", "application/json",
                json.writeValueAsString(neighbour).getBytes(StandardCharsets.UTF_8))).header("Idempotency-Key", UUID.randomUUID().toString()))
                .getStatus()).isEqualTo(403);
        String counterKey = UUID.randomUUID().toString();
        MockHttpServletResponse objected = object(counter, id, counterKey, neighbour, DocumentFixtures.pdf("/Lang (objection)"));
        assertThat(objected.getStatus()).as(objected.getContentAsString()).isEqualTo(201);
        String objectionId = json.readTree(objected.getContentAsString()).path("objection").asString("");
        MockHttpServletResponse retried = object(counter, id, counterKey, neighbour, DocumentFixtures.pdf("/Lang (objection)"));
        assertThat(retried.getStatus()).isEqualTo(201);
        assertThat(json.readTree(retried.getContentAsString()).path("objection").asString("")).isEqualTo(objectionId);
        JsonNode objections = json.readTree(retried.getContentAsString()).path("procedure").path("objections");
        assertThat(objections).as("a retry adds nothing").hasSize(1);
        assertThat(objections.get(0).path("files")).as("... not even its files").hasSize(1);
        assertThat(objections.get(0).path("late").asBoolean()).isFalse();
        assertThat(objections.get(0).path("objector").has("identityNumber")).as("no list carries the identity").isFalse();
        assertThat(objections.get(0).path("objector").path("identityVisible").asBoolean()).isTrue();
        MockHttpServletResponse identity = counter.browser().send(get(PROCEDURES + "/" + id + "/objections/" + objectionId + "/objector"));
        assertThat(identity.getStatus()).isEqualTo(200);
        assertThat(json.readTree(identity.getContentAsString()).path("identityNumber").asString("")).isEqualTo("DEMO-ID-7731");
        assertThat(identity.getHeader("Cache-Control")).contains("no-store");
        assertThat(audited("PROCEDURE_OBJECTOR_IDENTITY_READ", id)).as("each read of the identity is audited").isEqualTo(1);
        assertThat(audited("PROCEDURE_OBJECTION_RECORDED", id)).isEqualTo(1);

        // Every recommendation waits for the objection's decision.
        SignedIn reviewer = signedIn("REVIEWER");
        assertThat(act(reviewer, id, "review-claim", "{}").getStatus()).isEqualTo(200);
        MockHttpServletResponse held = act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id),
                "recommendation", "RECOMMEND_REFUSAL", "finding", "The occupation is not established"));
        assertThat(held.getStatus()).isEqualTo(409);
        assertThat(code(held)).isEqualTo("OBJECTION_OPEN");
        JsonNode asReviewer = view(reviewer, id);
        assertThat(asReviewer.path("objections").get(0).path("objector").path("identityVisible").asBoolean())
                .as("the reviewer reads the objection, never the objector's identity").isFalse();
        assertThat(reviewer.browser().send(get(PROCEDURES + "/" + id + "/objections/" + objectionId + "/objector")).getStatus()).isEqualTo(403);
        assertThat(audited("PROCEDURE_OBJECTOR_IDENTITY_READ", id)).isEqualTo(1);
        String objectionFile = asReviewer.path("objections").get(0).path("files").get(0).path("id").asString("");
        assertThat(reviewer.browser().send(get(PROCEDURES + "/" + id + "/objections/" + objectionId + "/files/" + objectionFile)).getStatus())
                .isEqualTo(200);
        assertThat(audited("PROCEDURE_OBJECTION_FILE_DOWNLOADED", id)).isEqualTo(1);

        // Decided by someone else, with grounds; upheld, it sends the visit back and never refuses the case.
        assertThat(code(decide(counter, id, objectionId, "DISMISSED", "The boundary was checked at the visit", List.of())))
                .isEqualTo("OBJECTION_RECORDER_CANNOT_DECIDE");
        assertThat(code(decide(supervisor, id, objectionId, "UPHELD", "Too short", List.of("FIELD_VISIT")))).isEqualTo("OBJECTION_GROUNDS_REQUIRED");
        assertThat(supervisor.browser().send(get(PROCEDURES + "/objection-queue")).getContentAsString()).contains(objectionId);
        MockHttpServletResponse upheld = decide(supervisor, id, objectionId, "UPHELD",
                "The markers must be checked again with the neighbour present", List.of("FIELD_VISIT"),
                "Be present at the new field visit with the boundary markers");
        assertThat(upheld.getStatus()).as(upheld.getContentAsString()).isEqualTo(200);
        JsonNode afterUpheld = json.readTree(upheld.getContentAsString());
        assertThat(afterUpheld.path("state").asString("")).isEqualTo("UNDER_REVIEW");
        assertThat(superuserText("SELECT count(*)::text FROM snigf.decision WHERE application_id = ?::uuid", id))
                .as("an upheld objection never decides the case").isEqualTo("0");
        // Only the stage it names is reopened.
        assertThat(stage(afterUpheld, "FIELD_VISIT").path("status").asString("")).isEqualTo("OUTDATED");
        assertThat(stage(afterUpheld, "CADASTRAL_REVIEW").path("status").asString("")).isEqualTo("COMPLETED");
        assertThat(stage(afterUpheld, "COMMISSION_REPORT").path("status").asString("")).isEqualTo("COMPLETED");
        // The applicant is told what to do again, never why.
        JsonNode told = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        JsonNode instruction = told.path("procedureInstructions").get(0);
        assertThat(instruction.path("stages").toString()).isEqualTo("[\"FIELD_VISIT\",\"PUBLICATION\"]");
        assertThat(instruction.path("action").asString("")).isEqualTo("Be present at the new field visit with the boundary markers");
        assertThat(instruction.path("pending").asBoolean()).isTrue();
        ApplicantPayloadSchema.assertSafe(told, "DEMO Neighbour Abena", "The markers must be checked again", "eastern boundary",
                afterUpheld.path("objections").get(0).path("number").asString("?"));
        assertThat(afterUpheld.path("gate").toString()).contains("OBJECTION_REVIEW_PENDING:FIELD_VISIT").doesNotContain("OBJECTION_OPEN");
        assertThat(code(decide(supervisor, id, objectionId, "DISMISSED", "A second decision on the same objection", List.of())))
                .isEqualTo("OBJECTION_ALREADY_DECIDED");
        assertThat(supervisor.browser().send(get(PROCEDURES + "/objection-queue")).getContentAsString()).doesNotContain(objectionId);

        // The review it asked for: the visit again, its report again, the notice published anew.
        Map<String, Object> again = rec("COMPLETED", "Markers checked again with the neighbour present", visit);
        again.put("supersedes", visitId);
        again.put("correctionReason", "Examined again after the neighbour's objection was upheld");
        assertThat(record(cadastral, id, "FIELD_VISIT", again).getStatus()).isEqualTo(200);
        Map<String, Object> tooEarly = rec("COMPLETED", "Posted anew on the office board", Map.of());
        tooEarly.put("supersedes", stage(view(intake, id), "PUBLICATION").path("records").get(0).path("id").asString(""));
        tooEarly.put("correctionReason", "Published anew before the report was made again");
        assertThat(code(record(intake, id, "PUBLICATION", tooEarly))).as("the report depends on the visit: it is reopened in turn")
                .isEqualTo("EARLIER_STAGES_INCOMPLETE");
        Map<String, Object> reportAgain = rec("COMPLETED", "The commission confirms the limits", report);
        reportAgain.put("supersedes", reportId);
        reportAgain.put("correctionReason", "The report follows the visit made again");
        assertThat(record(secretary, id, "COMMISSION_REPORT", reportAgain, DocumentFixtures.pdf("/Lang (pv2)")).getStatus()).isEqualTo(200);
        JsonNode beforeRepublishing = view(intake, id);
        assertThat(beforeRepublishing.path("gate").toString()).contains("PUBLISHED_BEFORE_CHANGE");
        Map<String, Object> republish = rec("COMPLETED", "Posted anew on the office board", Map.of());
        republish.put("supersedes", stage(beforeRepublishing, "PUBLICATION").path("records").get(0).path("id").asString(""));
        republish.put("correctionReason", "Published anew after the field visit was made again");
        JsonNode republished = json.readTree(record(intake, id, "PUBLICATION", republish).getContentAsString());
        assertThat(republished.path("notices")).hasSize(2);
        JsonNode renewed = republished.path("notices").get(0);
        assertThat(renewed.path("replaces").asString("")).isEqualTo(notice.path("id").asString(""));
        assertThat(renewed.path("number").asString("")).isNotEqualTo(noticeNumber);
        java.time.Instant opens = java.time.Instant.parse(renewed.path("opensAt").asString(""));
        assertThat(opens).as("a whole new period, opening now").isAfter(java.time.Instant.parse(notice.path("opensAt").asString("")));
        assertThat(java.time.Instant.parse(renewed.path("closesAt").asString(""))).isEqualTo(opens.plus(java.time.Duration.ofDays(30)));
        assertThat(renewed.path("periodOpen").asBoolean()).isTrue();
        JsonNode kept = republished.path("notices").get(1);
        assertThat(kept.path("number").asString("")).isEqualTo(noticeNumber);
        assertThat(kept.path("closesAt").asString("")).as("the first notice is kept as it was").isEqualTo(notice.path("closesAt").asString(""));
        assertThat(kept.path("current").asBoolean()).isFalse();

        // The period over, a late objection is still received, and holds the case until dismissed.
        periodOver(id);
        MockHttpServletResponse late = object(intake, id, UUID.randomUUID().toString(),
                objection("DEMO Heir Ondoa", "HEIR", "The heirs of the former occupant were never heard (synthetic)"));
        assertThat(late.getStatus()).as(late.getContentAsString()).isEqualTo(201);
        String lateId = json.readTree(late.getContentAsString()).path("objection").asString("");
        JsonNode afterLate = json.readTree(late.getContentAsString()).path("procedure");
        assertThat(afterLate.path("objections").get(1).path("late").asBoolean()).isTrue();
        assertThat(afterLate.path("gate").toString()).isEqualTo("[\"OBJECTION_OPEN\"]");
        assertThat(decide(supervisor, id, lateId, "DISMISSED", "The heirs were heard at the second visit (synthetic)", List.of()).getStatus())
                .isEqualTo(200);
        assertThat(view(reviewer, id).path("gate")).isEmpty();
        MockHttpServletResponse approval = act(reviewer, id, "recommendation", body("expectedVersion", version(reviewer, id),
                "recommendation", "RECOMMEND_APPROVAL", "finding", "The procedure is complete and every objection decided"));
        assertThat(approval.getStatus()).as(approval.getContentAsString()).isEqualTo(200);

        // The applicant: statuses only; the objector's identity is in no audit record.
        JsonNode mine = json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString());
        ApplicantPayloadSchema.assertSafe(mine, "DEMO Neighbour Abena", "DEMO Heir Ondoa", "DEMO-ID-7731", "eastern boundary");
        assertThat(audited("PROCEDURE_OBJECTION_DECIDED", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_event WHERE action = 'PROCEDURE_OBJECTION_DECIDED' AND entity_id = ?::uuid"
                + " ORDER BY chain_pos LIMIT 1", String.class, id)).isEqualTo("The markers must be checked again with the neighbour present");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND (coalesce(details::text, '') LIKE '%DEMO-ID-7731%'"
                + " OR coalesce(reason, '') LIKE '%DEMO-ID-7731%')", Integer.class, id)).isZero();
    }

    @Test
    void theGenericTypeKeepsNoProcedureAndItsCasesAreUngated() throws Exception {
        SignedIn cadastral = signedIn("CADASTRAL_OFFICER");
        Browser applicant = applicant();
        String id = underReview(applicant, parcel(cadastral, area(), 0));
        JsonNode view = view(cadastral, id);
        assertThat(view.path("procedure").isNull()).isTrue();
        assertThat(view.path("stages")).isEmpty();
        assertThat(view.path("gate")).isEmpty();
        assertThat(json.readTree(applicant.send(get(APPS + "/" + id)).getContentAsString()).path("stages")).isEmpty();
        assertThat(code(record(cadastral, id, "CADASTRAL_REVIEW", rec("COMPLETED", "The parcel matches the cadastre", Map.of()))))
                .isEqualTo("STAGE_NOT_IN_PROCEDURE");
    }
}
