// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.CaseFlowIntegrationTest;
import org.snigf.mvp.document.DocumentFixtures;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Slice 1 (ADR-039) through the real endpoints: the DEMO acknowledgment issued once after a
 * submission, its PDF, public verification by number and by file in every case (exact copy,
 * altered, recreated, unknown, external, revoked, superseded, registry rewritten), the document
 * officer's status changes and incident queue, the authorization, input and audit rules.
 */
class IssuanceIT extends CaseFlowIntegrationTest {

    private static final String PUBLIC = "/api/v1/public/verifications";

    private record Issued(String applicationId, String number, byte[] pdf, Browser applicant) {
    }

    private Issued issued() throws Exception {
        Browser applicant = applicant();
        String id = submitted(applicant, parcel(signedIn("CADASTRAL_OFFICER"), area(), 0));
        MockHttpServletResponse ack = applicant.send(get(APPS + "/" + id + "/acknowledgment"));
        assertThat(ack.getStatus()).as("issued after the submission committed: " + ack.getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(ack.getContentAsString());
        MockHttpServletResponse file = applicant.send(get(APPS + "/" + id + "/acknowledgment/file"));
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getHeader("Content-Disposition")).startsWith("attachment");
        return new Issued(id, body.path("number").asString(""), file.getContentAsByteArray(), applicant);
    }

    @Test
    void theAcknowledgmentIsIssuedOnceSignedAndReadableByItsApplicantAndTheOffice() throws Exception {
        Issued d = issued();
        assertThat(d.number()).matches("^SNIGF-[0-9A-HJKMNP-TV-Z]{26}$");
        assertThat(new String(d.pdf(), 0, 5)).isEqualTo("%PDF-");
        try (PDDocument pdf = Loader.loadPDF(d.pdf())) {
            assertThat(pdf.getDocumentInformation().getCustomMetadataValue("SNIGF-Document-Number")).isEqualTo(d.number());
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(text).contains("DÉMONSTRATION — SANS VALEUR LÉGALE", "DEMONSTRATION — NO LEGAL VALUE", d.number(),
                    "#/verify/" + d.number());
        }
        assertThat(single("SELECT encode(content_sha256, 'hex') FROM snigf.issued_document WHERE number = ?", d.number()))
                .isEqualTo(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d.pdf())));

        // Asking again, or many times at once, issues nothing more.
        assertThat(json.readTree(sendJson(d.applicant(), post(APPS + "/" + d.applicationId() + "/acknowledgment"), "{}")
                .getContentAsString()).path("number").asString("")).isEqualTo(d.number());
        List<String> numbers = concurrently(8, () -> json.readTree(sendJson(d.applicant(),
                post(APPS + "/" + d.applicationId() + "/acknowledgment"), "{}").getContentAsString()).path("number").asString(""));
        assertThat(numbers).containsOnly(d.number());
        assertThat(single("SELECT count(*)::text FROM snigf.issued_document WHERE subject_id = ?::uuid", d.applicationId()))
                .isEqualTo("1");

        // Its applicant and the office read it; nobody else.
        Browser other = applicant("Someone Else");
        assertThat(other.send(get(APPS + "/" + d.applicationId() + "/acknowledgment")).getStatus()).isEqualTo(404);
        assertThat(other.send(get(APPS + "/" + d.applicationId() + "/acknowledgment/file")).getStatus()).isEqualTo(404);
        SignedIn intake = signedIn("INTAKE_OFFICER");
        assertThat(intake.browser().send(get("/api/v1/cases/" + d.applicationId() + "/acknowledgment/file"))
                .getContentAsByteArray()).isEqualTo(d.pdf());
        assertThat(signedIn("INTAKE_OFFICER", "DEMOIT2").browser().send(get("/api/v1/cases/" + d.applicationId()
                + "/acknowledgment")).getStatus()).isEqualTo(403);
        assertThat(timelineActions(d.applicationId())).contains("DOCUMENT_ISSUED");
    }

    @Test
    void publicVerificationSaysWhatTheFileOrNumberProvesAndNothingPersonal() throws Exception {
        Issued d = issued();
        String applicantName = "Demo Applicant";
        String reference = json.readTree(d.applicant().send(get(APPS + "/" + d.applicationId())).getContentAsString())
                .path("reference").asString("");

        // By number, signed out: the record and its status; never the paper's content.
        MockHttpServletResponse byNumber = mvc.perform(get(PUBLIC + "/" + d.number().toLowerCase())).andReturn().getResponse();
        assertThat(byNumber.getStatus()).isEqualTo(200);
        JsonNode n = json.readTree(byNumber.getContentAsString());
        assertThat(n.path("outcome").asString("")).isEqualTo("RECORD_FOUND");
        assertThat(n.path("scope").asString("")).isEqualTo("RECORD_ONLY");
        assertThat(n.path("demo").asBoolean()).isTrue();
        assertThat(n.path("document").path("status").asString("")).isEqualTo("VALID");
        assertThat(n.path("document").path("issuingOffice").path("code").asString("")).isEqualTo("DEMOIT");
        assertThat(byNumber.getContentAsString()).doesNotContain(reference, applicantName, d.applicationId(), "DEMO-PCL");

        // The exact file, and a byte-for-byte copy of it: authentic.
        JsonNode exact = verify(d.pdf());
        assertThat(exact.path("outcome").asString("")).isEqualTo("AUTHENTIC");
        assertThat(exact.path("scope").asString("")).isEqualTo("EXACT_FILE");
        assertThat(verify(d.pdf().clone()).path("outcome").asString("")).isEqualTo("AUTHENTIC");

        // Edited after issuance (same number, other bytes): altered, and an incident.
        JsonNode altered = verify(edited(d.pdf()));
        assertThat(altered.path("outcome").asString("")).isEqualTo("ALTERED");
        assertThat(altered.path("reason").asString("")).isEqualTo("CONTENT_DIFFERS");
        // Recreated: a new PDF carrying the genuine number (the copied QR code): altered, never authentic.
        assertThat(verify(recreated(d.number())).path("outcome").asString("")).isEqualTo("ALTERED");
        // Fabricated: a well-formed number nobody issued: unverified, and an incident.
        JsonNode fabricated = verify(recreated("SNIGF-ZZZZZZZZZZZZZZZZZZZZZZZZZZ"));
        assertThat(fabricated.path("outcome").asString("")).isEqualTo("UNVERIFIED");
        assertThat(fabricated.path("reason").asString("")).isEqualTo("NO_ISSUANCE_RECORD");
        // An external document SNIGF did not issue: not ours, and not suspicious for that.
        JsonNode external = verify(DocumentFixtures.pdf("/Lang (fr)"));
        assertThat(external.path("outcome").asString("")).isEqualTo("UNVERIFIED");
        assertThat(external.path("reason").asString("")).isEqualTo("NO_SNIGF_EVIDENCE");
        assertThat(verify("not a pdf at all".getBytes()).path("reason").asString("")).isEqualTo("NOT_A_PDF");
        // An unknown typed number: recorded, not an incident (a typo is not evidence).
        assertThat(json.readTree(mvc.perform(get(PUBLIC + "/SNIGF-YYYYYYYYYYYYYYYYYYYYYYYYYY")).andReturn().getResponse()
                .getContentAsString()).path("outcome").asString("")).isEqualTo("UNVERIFIED");

        assertThat(incidentCount(altered.path("verification").asString(""))).isEqualTo(1);
        assertThat(incidentCount(fabricated.path("verification").asString(""))).isEqualTo(1);
        assertThat(incidentCount(external.path("verification").asString(""))).as("external: no incident").isZero();

        // Malformed input.
        assertThat(code(mvc.perform(get(PUBLIC + "/not-a-number")).andReturn().getResponse())).isEqualTo("INVALID_NUMBER");
        Browser anyone = new Browser();
        anyone.csrf();
        assertThat(code(anyone.send(multipart(PUBLIC).file(new MockMultipartFile("file", "big.pdf", "application/pdf",
                new byte[2_097_153]))))).isEqualTo("FILE_TOO_LARGE");
        assertThat(code(anyone.send(multipart(PUBLIC)))).isEqualTo("FILE_REQUIRED");
        assertThat(mvc.perform(multipart(PUBLIC).file(new MockMultipartFile("file", "a.pdf", "application/pdf", d.pdf())))
                .andReturn().getResponse().getStatus()).as("POST without the CSRF token").isEqualTo(403);
    }

    @Test
    void theDocumentOfficerRevokesOrReissuesAndVerificationFollowsTheStatus() throws Exception {
        Issued d = issued();
        SignedIn officer = signedIn("DOCUMENT_OFFICER");
        SignedIn intake = signedIn("INTAKE_OFFICER");
        assertThat(sendJson(intake.browser(), post("/api/v1/issued-documents/" + d.number() + "/revocation"),
                reason("Wrongly issued to this applicant")).getStatus()).isEqualTo(403);
        assertThat(code(sendJson(officer.browser(), post("/api/v1/issued-documents/" + d.number() + "/reissue"), reason("short"))))
                .isEqualTo("REASON_REQUIRED");

        // Re-issue: a new number; the old one is superseded and names it; the applicant reads the new one.
        JsonNode replacement = json.readTree(sendJson(officer.browser(), post("/api/v1/issued-documents/" + d.number() + "/reissue"),
                reason("Office code printed was corrected")).getContentAsString());
        String next = replacement.path("number").asString("");
        assertThat(next).isNotEqualTo(d.number()).matches("^SNIGF-.{26}$");
        assertThat(json.readTree(sendJson(officer.browser(), post("/api/v1/issued-documents/" + d.number() + "/reissue"),
                reason("Office code printed was corrected")).getContentAsString()).path("number").asString(""))
                .as("re-issuing again returns the same replacement").isEqualTo(next);
        JsonNode old = json.readTree(mvc.perform(get(PUBLIC + "/" + d.number())).andReturn().getResponse().getContentAsString());
        assertThat(old.path("outcome").asString("")).isEqualTo("SUPERSEDED");
        assertThat(old.path("document").path("replacement").asString("")).isEqualTo(next);
        assertThat(verify(d.pdf()).path("outcome").asString("")).as("the old file is genuine but superseded").isEqualTo("SUPERSEDED");
        assertThat(json.readTree(d.applicant().send(get(APPS + "/" + d.applicationId() + "/acknowledgment")).getContentAsString())
                .path("number").asString("")).isEqualTo(next);

        // Revoke the replacement: final; revoking twice is refused.
        assertThat(sendJson(officer.browser(), post("/api/v1/issued-documents/" + next + "/revocation"),
                reason("Application withdrawn by the office")).getStatus()).isEqualTo(200);
        assertThat(json.readTree(mvc.perform(get(PUBLIC + "/" + next)).andReturn().getResponse().getContentAsString())
                .path("outcome").asString("")).isEqualTo("REVOKED");
        assertThat(code(sendJson(officer.browser(), post("/api/v1/issued-documents/" + next + "/cancellation"),
                reason("Cancelling a revoked document")))).isEqualTo("NOT_VALID");
        assertThat(timelineActions(d.applicationId())).contains("DOCUMENT_ISSUED", "DOCUMENT_SUPERSEDED", "DOCUMENT_REVOKED");
    }

    @Test
    void incidentsAreReviewedOnlyByTheDocumentOfficerOnceWithANote() throws Exception {
        Issued d = issued();
        String verification = verify(edited(d.pdf())).path("verification").asString("");
        SignedIn officer = signedIn("DOCUMENT_OFFICER");
        assertThat(signedIn("SUPERVISOR").browser().send(get("/api/v1/verification-incidents")).getStatus()).isEqualTo(403);
        JsonNode open = json.readTree(officer.browser().send(get("/api/v1/verification-incidents").param("status", "OPEN"))
                .getContentAsString());
        JsonNode incident = null;
        for (JsonNode i : open) {
            if (d.number().equals(i.path("documentNumber").asString(""))) {
                incident = i;
            }
        }
        assertThat(incident).as("the altered file's incident is queued").isNotNull();
        assertThat(incident.path("kind").asString("")).isEqualTo("ALTERED");
        String id = incident.path("id").asString("");
        long version = incident.path("version").asLong();
        assertThat(code(sendJson(officer.browser(), post("/api/v1/verification-incidents/" + id + "/resolution"),
                json.writeValueAsString(Map.of("expectedVersion", version, "resolution", "CONFIRMED_ALTERATION", "note", "short")))))
                .isEqualTo("NOTE_REQUIRED");
        MockHttpServletResponse resolved = sendJson(officer.browser(), post("/api/v1/verification-incidents/" + id + "/resolution"),
                json.writeValueAsString(Map.of("expectedVersion", version, "resolution", "CONFIRMED_ALTERATION",
                        "note", "Holder name edited after issuance; holder informed")));
        assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(resolved.getContentAsString()).path("status").asString("")).isEqualTo("RESOLVED");
        assertThat(code(sendJson(officer.browser(), post("/api/v1/verification-incidents/" + id + "/resolution"),
                json.writeValueAsString(Map.of("expectedVersion", version + 1, "resolution", "REFERRED",
                        "note", "Trying to resolve it a second time"))))).isEqualTo("INCIDENT_RESOLVED");
        assertThat(verification).isNotEmpty();
        assertThat(timelineActions(d.applicationId())).contains("VERIFICATION_INCIDENT_OPENED", "VERIFICATION_INCIDENT_RESOLVED");
    }

    @Test
    void aRegistryRowRewrittenBehindTheApplicationsBackIsNeverTrusted() throws Exception {
        Issued d = issued();
        String original = single("SELECT subject_ref FROM snigf.issued_document WHERE number = ?", d.number());
        rewrite(d.number(), "DEMO-APP-2026-999999-X");
        try {
            JsonNode n = json.readTree(mvc.perform(get(PUBLIC + "/" + d.number())).andReturn().getResponse().getContentAsString());
            assertThat(n.path("outcome").asString("")).isEqualTo("UNVERIFIED");
            assertThat(n.path("reason").asString("")).isEqualTo("REGISTRY_INTEGRITY");
            assertThat(n.path("document").isNull()).isTrue();
            assertThat(verify(d.pdf()).path("reason").asString("")).isEqualTo("REGISTRY_INTEGRITY");
        } finally {
            rewrite(d.number(), original);
        }
        assertThat(verify(d.pdf()).path("outcome").asString("")).isEqualTo("AUTHENTIC");
    }

    // --- Helpers -------------------------------------------------------------------------------

    private JsonNode verify(byte[] file) throws Exception {
        Browser anyone = new Browser();
        anyone.csrf();
        MockHttpServletResponse response = anyone.send(multipart(PUBLIC)
                .file(new MockMultipartFile("file", "presented.pdf", "application/pdf", file)));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    /** The genuine file edited after issuance (a title changed and saved again): same number, other bytes. */
    private static byte[] edited(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("Holder: someone else");
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A new PDF that copies an SNIGF number in its metadata, as a forger copying the QR code would. */
    private static byte[] recreated(String number) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setProducer("SNIGF (DEMO)");
            document.getDocumentInformation().setCustomMetadataValue("SNIGF-Document-Number", number);
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String reason(String text) {
        return "{\"reason\":\"" + text + "\"}";
    }

    private static long incidentCount(String verificationId) throws Exception {
        return Long.parseLong(single("SELECT count(*)::text FROM snigf.verification_incident WHERE verification_id = ?::uuid",
                verificationId));
    }

    private static List<String> timelineActions(String applicationId) throws Exception {
        List<String> actions = new ArrayList<>();
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             PreparedStatement q = su.prepareStatement("SELECT action FROM snigf.audit_event WHERE subjects @> ?::jsonb ORDER BY chain_pos")) {
            q.setString(1, "[{\"type\":\"APPLICATION\",\"id\":\"" + applicationId + "\"}]");
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    actions.add(rs.getString(1));
                }
            }
        }
        return actions;
    }

    /** A database administrator rewriting a registry row (triggers off: superuser only). */
    private static void rewrite(String number, String subjectRef) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection();
             Statement replica = su.createStatement();
             PreparedStatement update = su.prepareStatement("UPDATE snigf.issued_document SET subject_ref = ? WHERE number = ?")) {
            replica.execute("SET session_replication_role = replica");
            update.setString(1, subjectRef);
            update.setString(2, number);
            assertThat(update.executeUpdate()).isEqualTo(1);
            replica.execute("SET session_replication_role = origin");
            su.commit();
        }
    }

    private static String single(String sql, String parameter) throws Exception {
        try (Connection su = PostgisDatabase.shared().superuserConnection(); PreparedStatement q = su.prepareStatement(sql)) {
            q.setString(1, parameter);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static <T> List<T> concurrently(int copies, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
