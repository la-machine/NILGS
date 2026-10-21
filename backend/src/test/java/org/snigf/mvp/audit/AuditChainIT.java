// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.application.AuditProperties;
import org.snigf.mvp.audit.application.CheckpointArchive;
import org.snigf.mvp.audit.domain.ChainVerifier;
import org.snigf.mvp.audit.domain.CheckpointEvidence;
import org.snigf.mvp.audit.infrastructure.persistence.JdbcAuditEventRepository;
import org.snigf.mvp.audit.infrastructure.persistence.JdbcCheckpointRepository;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1-AC5 and C7 end to end, against real PostgreSQL: signed checkpoints, offline
 * verification, and tampering by the PostgreSQL superuser with the append-only triggers
 * disabled (the residual-risk actor of docs/audit.md).
 */
class AuditChainIT extends AuditIntegrationTest {

    @Autowired
    KeyService keys;

    @Autowired
    CheckpointArchive archive;

    @Autowired
    AuditProperties properties;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    HealthIndicator auditChain;

    @Test
    void aCheckpointIsSignedStoredTwiceAndVerifiableOfflineWithThePublishedKey() throws Exception {
        recordEvent("before checkpoint");

        Checkpoint checkpoint = trail.createCheckpoint();

        assertThat(trail.checkpoints()).contains(checkpoint);
        byte[] file = trail.checkpointFile(checkpoint.chainPos()).orElseThrow();
        JsonNode doc = json.readTree(file);
        assertThat(doc.path("headHash").asString("")).isEqualTo(checkpoint.headHashHex());
        assertThat(jdbc.queryForObject("SELECT encode(hash, 'hex') FROM audit_event WHERE chain_pos = ?", String.class,
                checkpoint.chainPos())).isEqualTo(checkpoint.headHashHex());

        // An auditor's check, with nothing from the application but the two published files:
        // rebuild the payload as docs/audit.md describes it and verify with the PEM key.
        String payload = String.join("|", "snigf-audit-checkpoint-v1",
                f(doc.path("id").asString("")), f(Long.toString(doc.path("chainPos").asLong())),
                f(doc.path("headHash").asString("")), f(doc.path("createdAt").asString("")));
        JsonNode published = json.readTree(properties.checkpointDirectory().resolve("checkpoint-keys.json").toFile());
        String pem = published.path("keys").get(doc.path("keyVersion").asInt() - 1).path("pem").asString("");
        java.security.Signature ed = java.security.Signature.getInstance("Ed25519");
        ed.initVerify(publicKey(pem));
        ed.update(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(ed.verify(Base64.getDecoder().decode(doc.path("signature").asString("")))).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'AUDIT_CHECKPOINT_CREATED'"
                + " AND entity_id = ?", Long.class, checkpoint.id())).isEqualTo(1);
        assertThatThrownBy(() -> archive.store(checkpoint)).hasMessageContaining("never replaced");
    }

    @Test
    void aFullVerificationPassesAndIsItselfAudited() {
        recordEvent("verify me");
        trail.createCheckpoint();

        ChainVerification v = trail.verify(null, null);

        assertThat(v.verified()).isTrue();
        assertThat(v.checkedFrom()).isEqualTo(1);
        assertThat(v.lastCheckpoint()).hasValueSatisfying(c -> {
            assertThat(c.matches()).isTrue();
            assertThat(c.inDatabase()).isTrue();
            assertThat(c.inFile()).isTrue();
        });
        assertThat(auditChain.health().getStatus()).isEqualTo(Status.UP);
        assertThat(jdbc.queryForObject("SELECT action FROM audit_event ORDER BY chain_pos DESC LIMIT 1", String.class))
                .isEqualTo("AUDIT_VERIFIED");
    }

    @Test
    void superuserEditsAreDetectedAtTheirPosition() throws Exception {
        long pos = recordEvent("to edit").chainPos();
        recordEvent("after edit");

        try (Connection su = PostgisDatabase.shared().superuserConnection()) {
            exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
            exec(su, "UPDATE audit_event SET details = '{\"marker\": \"REWRITTEN\"}' WHERE chain_pos = " + pos);
            assertFailure(verifyAs(su), pos, MismatchKind.CONTENT_HASH);

            exec(su, "UPDATE audit_event SET hash = sha256(prev_hash || audit_canonical(audit_event.*)) WHERE chain_pos = " + pos);
            assertFailure(verifyAs(su), pos + 1, MismatchKind.PREV_HASH);
            su.rollback();
        }
    }

    @Test
    void aSuperuserDeletionIsAGap() throws Exception {
        long pos = recordEvent("to delete").chainPos();
        recordEvent("after delete");

        try (Connection su = PostgisDatabase.shared().superuserConnection()) {
            exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
            exec(su, "DELETE FROM audit_event WHERE chain_pos = " + pos);
            assertFailure(verifyAs(su), pos, MismatchKind.GAP);
            su.rollback();
        }
    }

    @Test
    void aWholeChainRewriteIsCaughtByTheSignedCheckpoint() throws Exception {
        long pos = recordEvent("to rewrite").chainPos();
        Checkpoint checkpoint = trail.createCheckpoint();

        try (Connection su = PostgisDatabase.shared().superuserConnection()) {
            rewriteFrom(su, pos);
            ChainVerification v = verifyAs(su);
            assertThat(v.rowsChecked()).as("the recomputed chain is consistent").isPositive();
            assertThat(v.mismatchKind()).isEqualTo(MismatchKind.CHECKPOINT_MISMATCH);
            assertThat(v.firstMismatchPos()).isLessThanOrEqualTo(checkpoint.chainPos());
            su.rollback();
        }
    }

    @Test
    void deletingTheNewestEventsIsCaughtAsTruncation() throws Exception {
        recordEvent("newest");
        Checkpoint checkpoint = trail.createCheckpoint();

        try (Connection su = PostgisDatabase.shared().superuserConnection()) {
            exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
            exec(su, "ALTER TABLE audit_checkpoint DISABLE TRIGGER audit_checkpoint_no_change");
            // Remove the evidence from the database too: the archived file still holds it.
            exec(su, "DELETE FROM audit_checkpoint WHERE chain_pos >= " + checkpoint.chainPos());
            exec(su, "DELETE FROM audit_event WHERE chain_pos >= " + checkpoint.chainPos());
            ChainVerification v = verifyAs(su);
            assertThat(v.mismatchKind()).isEqualTo(MismatchKind.TRUNCATED);
            assertThat(v.checkpoints()).anySatisfy(c -> {
                assertThat(c.chainPos()).isEqualTo(checkpoint.chainPos());
                assertThat(c.inDatabase()).isFalse();
                assertThat(c.inFile()).isTrue();
            });
            su.rollback();
        }
    }

    @Test
    void aTamperedChainIsNeverSignedAndTheRefusalIsAudited() throws Exception {
        recordEvent("anchor");
        trail.createCheckpoint();
        long pos = recordEvent("tampered before checkpoint").chainPos();
        String original = jdbc.queryForObject("SELECT details::text FROM audit_event WHERE chain_pos = ?", String.class, pos);

        try (Connection su = PostgisDatabase.shared().superuserConnection()) {
            exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
            exec(su, "UPDATE audit_event SET details = '{\"marker\": \"REWRITTEN\"}' WHERE chain_pos = " + pos);
            exec(su, "ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_change");
            su.commit();
            try {
                assertThatThrownBy(() -> trail.createCheckpoint())
                        .isInstanceOf(AuditIntegrityException.class)
                        .satisfies(e -> assertThat(((AuditIntegrityException) e).verification().firstMismatchPos())
                                .isEqualTo(pos));
                assertThat(auditChain.health().getStatus()).isEqualTo(Status.DOWN);
                assertThat(jdbc.queryForObject("SELECT outcome FROM audit_event ORDER BY chain_pos DESC LIMIT 1",
                        String.class)).isEqualTo("FAILED");
            } finally {
                exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
                try (var restore = su.prepareStatement(
                        "UPDATE audit_event SET details = ?::jsonb WHERE chain_pos = ?")) {
                    restore.setString(1, original);
                    restore.setLong(2, pos);
                    restore.executeUpdate();
                }
                exec(su, "ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_change");
                su.commit();
            }
        }
        assertThat(trail.verify(null, null).verified()).as("restored").isTrue();
        assertThat(auditChain.health().getStatus()).isEqualTo(Status.UP);
    }

    /** The domain verifier on the superuser's uncommitted view of the chain. */
    private ChainVerification verifyAs(Connection su) {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(su, true);
        JdbcClient client = JdbcClient.create(ds);
        CheckpointArchive.Contents archived = archive.readAll();
        List<CheckpointEvidence> evidence = CheckpointEvidence.merge(new JdbcCheckpointRepository(client).all(),
                archived.checkpoints(), archived.unreadable(),
                c -> keys.verify(SigningKey.AUDIT_CHECKPOINT, c.payload(), c.signature()));
        return new ChainVerifier(new JdbcAuditEventRepository(client, ds, properties)).verify(null, null, evidence);
    }

    /** Edits one event and recomputes every later link and hash, as a superuser could. */
    private static void rewriteFrom(Connection su, long pos) throws Exception {
        exec(su, "ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
        exec(su, "UPDATE audit_event SET details = '{\"marker\": \"REWRITTEN\"}' WHERE chain_pos = " + pos);
        exec(su, """
                DO $$
                DECLARE p bigint;
                BEGIN
                    FOR p IN SELECT chain_pos FROM audit_event WHERE chain_pos >= %d ORDER BY chain_pos LOOP
                        UPDATE audit_event SET prev_hash = (SELECT hash FROM audit_event WHERE chain_pos = p - 1)
                         WHERE chain_pos = p;
                        UPDATE audit_event SET hash = sha256(prev_hash || audit_canonical(audit_event.*))
                         WHERE chain_pos = p;
                    END LOOP;
                END $$""".formatted(pos));
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertFailure(ChainVerification v, long pos, MismatchKind kind) {
        assertThat(v.verified()).isFalse();
        assertThat(v.firstMismatchPos()).isEqualTo(pos);
        assertThat(v.mismatchKind()).isEqualTo(kind);
    }

    private static String f(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private static PublicKey publicKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
