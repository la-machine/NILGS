// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The auditor's API (S1-AC5): search, verify, checkpoint, download, all through the real
 * endpoints with a fully authenticated AUDITOR; every read is itself audited.
 */
class AuditApiIT extends IdentityIntegrationTest {

    static final String AUDIT = "/api/v1/audit";

    @Test
    void anAuditorSearchesEventsAndTheSearchIsItselfAudited() throws Exception {
        SignedIn auditor = signedIn("AUDITOR");
        SignedIn subject = signedIn("REVIEWER");

        MockHttpServletResponse response = auditor.browser().send(get(AUDIT + "/events")
                .param("actorId", subject.id().toString()).param("action", "MFA_ENROLLED").param("size", "10"));
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.path("events")).hasSize(2);
        JsonNode first = body.path("events").get(0);
        assertThat(first.path("details").path("method").asString("")).as("details as JSON").isEqualTo("WEBAUTHN");
        assertThat(first.path("hash").asString("")).hasSize(64);
        assertThat(first.path("chainPos").asLong()).isGreaterThan(body.path("events").get(1).path("chainPos").asLong());

        assertThat(jdbc.queryForObject("SELECT details->>'actorId' FROM audit_event WHERE action = 'AUDIT_EVENTS_READ'"
                + " AND actor_id = ? ORDER BY chain_pos DESC LIMIT 1", String.class, auditor.id()))
                .isEqualTo(subject.id().toString());
    }

    @Test
    void anAuditorVerifiesSignsAndDownloadsACheckpointVerifiableOffline() throws Exception {
        SignedIn auditor = signedIn("AUDITOR");

        MockHttpServletResponse verified = sendJson(auditor.browser(), post(AUDIT + "/verify"), "{}");
        assertThat(verified.getStatus()).isEqualTo(200);
        assertThat(json.readTree(verified.getContentAsString()).path("verified").asBoolean()).isTrue();

        MockHttpServletResponse created = sendJson(auditor.browser(), post(AUDIT + "/checkpoints"), "{}");
        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode checkpoint = json.readTree(created.getContentAsString());
        String file = checkpoint.path("file").asString("");
        assertThat(created.getHeader("Location")).isEqualTo(file);

        MockHttpServletResponse download = auditor.browser().send(get(file));
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getHeader("Content-Disposition")).contains("checkpoint-" + checkpoint.path("chainPos").asLong());
        JsonNode doc = json.readTree(download.getContentAsByteArray());

        JsonNode keys = json.readTree(auditor.browser().send(get(AUDIT + "/checkpoint-keys")).getContentAsString());
        String pem = keys.get(doc.path("keyVersion").asInt() - 1).path("pem").asString("")
                .replaceAll("-----[A-Z ]+-----|\\s", "");
        java.security.Signature ed = java.security.Signature.getInstance("Ed25519");
        ed.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem))));
        ed.update(String.join("|", "snigf-audit-checkpoint-v1", f(doc.path("id").asString("")),
                f(Long.toString(doc.path("chainPos").asLong())), f(doc.path("headHash").asString("")),
                f(doc.path("createdAt").asString(""))).getBytes(StandardCharsets.UTF_8));
        assertThat(ed.verify(Base64.getDecoder().decode(doc.path("signature").asString("")))).isTrue();

        assertThat(json.readTree(auditor.browser().send(get(AUDIT + "/checkpoints")).getContentAsString()))
                .anySatisfy(c -> assertThat(c.path("id").asString("")).isEqualTo(checkpoint.path("id").asString("")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'AUDIT_EVENTS_READ'"
                + " AND actor_id = ? AND details->>'checkpointFile' IS NOT NULL", Long.class, auditor.id())).isEqualTo(1);
    }

    @Test
    void aBrokenChainIsReportedAndNeverSigned() throws Exception {
        SignedIn auditor = signedIn("AUDITOR");
        sendJson(auditor.browser(), post(AUDIT + "/checkpoints"), "{}");
        long pos = jdbc.queryForObject("SELECT max(chain_pos) FROM audit_event", Long.class);
        String original = jdbc.queryForObject("SELECT details::text FROM audit_event WHERE chain_pos = ?", String.class, pos);

        try (Connection su = PostgisDatabase.shared().superuserConnection()) {
            exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
            exec(su, "UPDATE audit_event SET details = '{\"tampered\": true}' WHERE chain_pos = " + pos);
            exec(su, "ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_change");
            su.commit();
            try {
                MockHttpServletResponse refused = sendJson(auditor.browser(), post(AUDIT + "/checkpoints"), "{}");
                assertThat(refused.getStatus()).isEqualTo(409);
                JsonNode problem = json.readTree(refused.getContentAsString());
                assertThat(problem.path("code").asString("")).isEqualTo("AUDIT_CHAIN_BROKEN");
                assertThat(problem.path("firstMismatchPos").asLong()).isEqualTo(pos);

                JsonNode verification = json.readTree(sendJson(auditor.browser(), post(AUDIT + "/verify"), "{}")
                        .getContentAsString());
                assertThat(verification.path("verified").asBoolean()).isFalse();
                assertThat(verification.path("mismatchKind").asString("")).isEqualTo("CONTENT_HASH");
            } finally {
                exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
                try (var restore = su.prepareStatement("UPDATE audit_event SET details = ?::jsonb WHERE chain_pos = ?")) {
                    restore.setString(1, original);
                    restore.setLong(2, pos);
                    restore.executeUpdate();
                }
                exec(su, "ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_change");
                su.commit();
            }
        }
        assertThat(json.readTree(sendJson(auditor.browser(), post(AUDIT + "/verify"), "{}").getContentAsString())
                .path("verified").asBoolean()).as("restored").isTrue();
    }

    @Test
    void invalidSearchesAndRangesAreRefused() throws Exception {
        SignedIn auditor = signedIn("AUDITOR");
        assertThat(auditor.browser().send(get(AUDIT + "/events").param("size", "1000")).getStatus()).isEqualTo(422);
        assertThat(auditor.browser().send(get(AUDIT + "/events").param("action", "drop table")).getStatus()).isEqualTo(422);
        assertThat(sendJson(auditor.browser(), post(AUDIT + "/verify"), "{\"fromPos\":5,\"toPos\":2}").getStatus())
                .isEqualTo(422);
    }

    @Test
    void onlyAuditorsReadTheAuditTrail() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        assertThat(admin.browser().send(get(AUDIT + "/events")).getStatus()).isEqualTo(403);
        assertThat(sendJson(admin.browser(), post(AUDIT + "/checkpoints"), "{}").getStatus()).isEqualTo(403);
    }

    private static String f(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
