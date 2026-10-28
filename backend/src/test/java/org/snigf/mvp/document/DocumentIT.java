// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.application.DocumentService;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * MVP-02 / E-13 through the real endpoints (Baseline tests: "disguised file rejected; orphan
 * cleanup"): the owner uploads PDF, JPEG or PNG to their draft, versions accumulate, a download
 * is an attachment whose bytes match their hash; a disguised or oversized file is refused and
 * audited with nothing kept; nobody else reaches the files; a submitted application takes no
 * more; files nobody references are removed; altered bytes are never served.
 */
class DocumentIT extends IdentityIntegrationTest {

    static final String APPS = "/api/v1/applications";

    @Autowired
    DocumentService documents;

    @Autowired
    DocumentProperties settings;

    @Test
    void theOwnerUploadsVersionsAndDownloadsThemAsAttachmentsMatchingTheirHash() throws Exception {
        Browser applicant = applicant();
        String id = draft(applicant);

        MockHttpServletResponse first = upload(applicant, id, "DEMO_IDENTITY", "carte.pdf", DocumentFixtures.pdf(), key());
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
        JsonNode uploaded = json.readTree(first.getContentAsString());
        assertThat(uploaded.path("versionNo").asInt()).isEqualTo(1);
        assertThat(uploaded.path("type").asString("")).isEqualTo("application/pdf");
        MockHttpServletResponse second = upload(applicant, id, "DEMO_IDENTITY", "carte-v2.pdf", DocumentFixtures.pdf("/Lang (fr)"), key());
        assertThat(json.readTree(second.getContentAsString()).path("versionNo").asInt()).isEqualTo(2);
        assertThat(upload(applicant, id, "DEMO_SITE_PLAN", "plan.png", DocumentFixtures.png(60, 40), key()).getStatus()).isEqualTo(201);

        JsonNode listed = json.readTree(applicant.send(get(APPS + "/" + id + "/documents")).getContentAsString());
        assertThat(listed.path("missingRequired").toString()).contains("DEMO_PROOF_OF_RIGHT").doesNotContain("DEMO_IDENTITY");
        JsonNode identity = listed.path("categories").get(0);
        assertThat(identity.path("code").asString("")).isEqualTo("DEMO_IDENTITY");
        assertThat(identity.path("versions").size()).as("both versions kept, newest first").isEqualTo(2);
        assertThat(identity.path("versions").get(0).path("originalName").asString("")).isEqualTo("carte-v2.pdf");

        String versionId = uploaded.path("id").asString("");
        MockHttpServletResponse download = applicant.send(get(APPS + "/" + id + "/documents/" + versionId + "/content"));
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentAsByteArray()).isEqualTo(DocumentFixtures.pdf());
        assertThat(download.getHeader("Content-Disposition")).startsWith("attachment;").contains("carte.pdf");
        assertThat(download.getHeader("Content-Type")).isEqualTo("application/pdf");
        assertThat(download.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download.getHeader("Content-Security-Policy")).contains("sandbox");
        assertThat(download.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = 'DOCUMENT_DOWNLOADED'",
                Long.class, versionId)).isEqualTo(1);
    }

    @Test
    void aDisguisedFileIsRefusedAuditedAndNothingIsKept() throws Exception {
        Browser applicant = applicant();
        String id = draft(applicant);
        MockHttpServletResponse page = upload(applicant, id, "DEMO_IDENTITY", "identity.pdf", DocumentFixtures.html(), key());
        assertThat(page.getStatus()).isEqualTo(422);
        assertThat(code(page)).isEqualTo("TYPE_NOT_ALLOWED");
        assertThat(code(upload(applicant, id, "DEMO_IDENTITY", "setup.pdf", DocumentFixtures.executable(), key())))
                .isEqualTo("TYPE_NOT_ALLOWED");
        assertThat(code(upload(applicant, id, "DEMO_IDENTITY", "photo.pdf", DocumentFixtures.png(20, 20), key())))
                .isEqualTo("TYPE_MISMATCH");
        assertThat(code(upload(applicant, id, "DEMO_IDENTITY", "form.pdf", DocumentFixtures.pdf("/AA << /O << /S /JavaScript >> >>"), key())))
                .isEqualTo("PDF_ACTIVE_CONTENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = 'DOCUMENT_REJECTED'"
                + " AND details->>'code' = 'TYPE_NOT_ALLOWED'", Long.class, id)).isEqualTo(2);
        JsonNode listed = json.readTree(applicant.send(get(APPS + "/" + id + "/documents")).getContentAsString());
        assertThat(listed.path("categories").get(0).path("versions").size()).as("nothing kept").isZero();

        MockHttpServletResponse big = upload(applicant, id, "DEMO_IDENTITY", "big.pdf", new byte[(int) settings.maxBytes() + 1], key());
        assertThat(big.getStatus()).isEqualTo(413);
        assertThat(code(big)).isEqualTo("FILE_TOO_LARGE");
        assertThat(code(upload(applicant, id, "DEMO_SALARY", "a.pdf", DocumentFixtures.pdf(), key()))).isEqualTo("CATEGORY_UNKNOWN");
    }

    @Test
    void nobodyElseReachesTheFilesAndARetryWithTheSameKeyKeepsOneVersion() throws Exception {
        Browser owner = applicant();
        String id = draft(owner);
        String key = key();
        String versionId = json.readTree(upload(owner, id, "DEMO_IDENTITY", "a.pdf", DocumentFixtures.pdf(), key)
                .getContentAsString()).path("id").asString("");
        MockHttpServletResponse retry = upload(owner, id, "DEMO_IDENTITY", "a.pdf", DocumentFixtures.pdf(), key);
        assertThat(retry.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json.readTree(retry.getContentAsString()).path("id").asString("")).isEqualTo(versionId);

        Browser other = applicant();
        assertThat(upload(other, id, "DEMO_IDENTITY", "b.pdf", DocumentFixtures.pdf(), key()).getStatus()).isEqualTo(404);
        assertThat(other.send(get(APPS + "/" + id + "/documents")).getStatus()).isEqualTo(404);
        assertThat(other.send(get(APPS + "/" + id + "/documents/" + versionId + "/content")).getStatus()).isEqualTo(404);
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        assertThat(officer.browser().send(get(APPS + "/" + id + "/documents/" + versionId + "/content")).getStatus())
                .as("staff: not their own records").isEqualTo(403);

        assertThat(upload(owner, id, "DEMO_IDENTITY", "c.pdf", DocumentFixtures.pdf("/Lang (en)"), key()).getStatus()).isEqualTo(201);
        assertThat(upload(owner, id, "DEMO_IDENTITY", "d.pdf", DocumentFixtures.pdf("/Lang (de)"), key()).getStatus()).isEqualTo(201);
        assertThat(code(upload(owner, id, "DEMO_IDENTITY", "e.pdf", DocumentFixtures.pdf("/Lang (it)"), key())))
                .as("SNIGF_DOCUMENT_MAX_VERSIONS is 3 in tests").isEqualTo("VERSION_LIMIT_REACHED");
    }

    @Test
    void aSubmittedApplicationTakesNoMoreFiles() throws Exception {
        Browser applicant = applicant();
        String id = draft(applicant);
        sendJson(applicant, put(APPS + "/" + id), json.writeValueAsString(Map.of("expectedVersion", 0,
                "parcelReference", parcel(), "data", Map.of("applicantCapacity", "OWNER", "landUse", "AGRICULTURAL",
                        "statement", "Bought in 2021 (synthetic data)"))));
        assertThat(sendJson(applicant, post(APPS + "/" + id + "/submission").header("Idempotency-Key", key()),
                "{\"expectedVersion\":1}").getStatus()).isEqualTo(200);
        MockHttpServletResponse late = upload(applicant, id, "DEMO_IDENTITY", "a.pdf", DocumentFixtures.pdf(), key());
        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("DOCUMENTS_CLOSED");
    }

    @Test
    void filesNobodyReferencesAreRemovedAndAlteredBytesAreNeverServed() throws Exception {
        Browser applicant = applicant();
        String id = draft(applicant);
        String versionId = json.readTree(upload(applicant, id, "DEMO_IDENTITY", "a.pdf", DocumentFixtures.pdf(), key())
                .getContentAsString()).path("id").asString("");
        Path kept = file(storageKey(versionId));
        Files.setLastModifiedTime(kept, FileTime.from(Instant.now().minus(Duration.ofDays(1))));

        byte[] random = new byte[16];
        ThreadLocalRandom.current().nextBytes(random);
        String orphanKey = HexFormat.of().formatHex(random);
        Path orphan = file(orphanKey);
        Files.createDirectories(orphan.getParent());
        Files.write(orphan, DocumentFixtures.pdf());
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(Duration.ofDays(1))));

        assertThat(SystemActor.call("document-cleanup", documents::removeOrphans)).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(orphan)).as("nobody references it").isFalse();
        assertThat(Files.exists(kept)).as("a version names it").isTrue();

        kept.toFile().setWritable(true);
        Files.write(kept, DocumentFixtures.pdf("/Tampered true"));
        MockHttpServletResponse altered = applicant.send(get(APPS + "/" + id + "/documents/" + versionId + "/content"));
        assertThat(altered.getStatus()).isEqualTo(500);
        assertThat(code(altered)).isEqualTo("DOCUMENT_INTEGRITY_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid AND action = 'DOCUMENT_INTEGRITY_FAILED'",
                Long.class, versionId)).isEqualTo(1);
    }

    private Path file(String storageKey) {
        return settings.directory().resolve(storageKey.substring(0, 2)).resolve(storageKey.substring(2, 4)).resolve(storageKey);
    }

    /** The storage key, read as the schema owner (row-level security hides it from the application's account). */
    private static String storageKey(String versionId) throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.PreparedStatement q = su.prepareStatement("SELECT storage_key FROM snigf.document_version WHERE id = ?::uuid")) {
            q.setString(1, versionId);
            try (java.sql.ResultSet rs = q.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private MockHttpServletResponse upload(Browser who, String id, String category, String name, byte[] bytes, String key)
            throws Exception {
        who.csrf();
        return who.send(multipart(APPS + "/" + id + "/documents/" + category)
                .file(new MockMultipartFile("file", name, "application/pdf", bytes))
                .header("Idempotency-Key", key));
    }

    private Browser applicant() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Demo Applicant", PASSWORD);
        browser.login(email, PASSWORD);
        browser.csrf();
        return browser;
    }

    private String draft(Browser applicant) throws Exception {
        MockHttpServletResponse created = sendJson(applicant, post(APPS).header("Idempotency-Key", key()),
                "{\"typeCode\":\"DEMO_REGISTRATION\",\"data\":{}}");
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return json.readTree(created.getContentAsString()).path("id").asString("");
    }

    private String parcel() throws Exception {
        SignedIn officer = signedIn("CADASTRAL_OFFICER");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double e = 300_000 + r.nextLong(0, 200) * 1000;
        double n = 500_000 + r.nextLong(0, 300) * 1000;
        String square = "{\"type\":\"Polygon\",\"coordinates\":[[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]}".formatted(
                e, n, e + 20, n, e + 20, n + 20, e, n + 20, e, n);
        MockHttpServletResponse response = sendJson(officer.browser(), post("/api/v1/parcels").header("Idempotency-Key", key()),
                "{\"officeCode\":\"DEMOIT\",\"locality\":\"Document fixture\",\"srid\":32633,\"geometry\":" + square + "}");
        return json.readTree(response.getContentAsString()).path("reference").asString("");
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("code").asString("");
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }
}
