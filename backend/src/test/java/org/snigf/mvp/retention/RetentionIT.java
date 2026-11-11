// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.retention;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR-041 D18 through the real endpoints: classes with no schedule, read by auditors and system administrators only and
 * audited; a hold placed by one system administrator, released only when a second approves the first one's request;
 * every refusal audited; a record's retention read without opening it, its disposal never allowed; nothing deleted.
 */
class RetentionIT extends IdentityIntegrationTest {

    private static final String BASE = "/api/v1/records";

    private String body(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(map);
    }

    private JsonNode ok(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("code").asString("");
    }

    private long audited(String action, String entity) {
        return entity == null ? jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ?", Long.class, action)
                : jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND entity_id = ?::uuid", Long.class, action, entity);
    }

    /** A piece of verification evidence of this professional, written by the superuser as the fixture of an upload. */
    private UUID evidenceOf(UUID professional) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement()) {
            st.execute("INSERT INTO professional_verification_document (id, verification_id, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)"
                    + " SELECT '" + id + "', v.id, '" + UUID.randomUUID().toString().replace("-", "") + "', repeat('f', 64), 10, 'application/pdf',"
                    + " 'chamber-letter.pdf', v.verified_by FROM professional_verification v WHERE v.user_id = '" + professional + "'");
            su.commit();
        }
        return id;
    }

    @Test
    void auditorsAndSystemAdministratorsReadRetentionAndNobodyElse() throws Exception {
        SignedIn auditor = signedIn("AUDITOR");
        JsonNode classes = ok(auditor.browser().send(get(BASE + "/retention-classes")), 200);
        assertThat(classes.findValuesAsString("code")).containsExactly("DEPOSIT_DOCUMENT", "DEPOSIT_DOCUMENT_REPLACEMENT", "PROFESSIONAL_EVIDENCE");
        assertThat(classes.findValuesAsString("standing")).containsOnly("NO_APPROVED_SCHEDULE_KEPT");
        classes.forEach(c -> assertThat(c.path("scheduleReference").isNull()).isTrue());
        assertThat(audited("RECORD_RETENTION_READ", null)).isPositive();
        assertThat(signedIn("SYSTEM_ADMIN").browser().send(get(BASE + "/retention-classes")).getStatus()).isEqualTo(200);

        for (String role : List.of("CONSERVATOR", "INTAKE_OFFICER", "SUPERVISOR")) {
            assertThat(signedIn(role).browser().send(get(BASE + "/holds")).getStatus()).as(role).isEqualTo(403);
        }
        assertThat(activeProfessional("NOTARY", "DEMOIT").browser().send(get(BASE + "/retention-classes")).getStatus()).isEqualTo(403);
        assertThat(sendJson(auditor.browser(), post(BASE + "/holds"), body("subjectKind", "PROFESSIONAL", "subjectId", UUID.randomUUID(),
                "reason", "An auditor only reads")).getStatus()).as("an auditor never places a hold").isEqualTo(403);
    }

    @Test
    void aHoldIsPlacedByOneAndReleasedByTwoAndNothingIsEverDisposedOf() throws Exception {
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        SignedIn auditor = signedIn("AUDITOR");
        SignedIn notary = activeProfessional("NOTARY", "DEMOIT");
        UUID evidence = evidenceOf(notary.id());

        assertThat(code(sendJson(first.browser(), post(BASE + "/holds"), body("subjectKind", "PROFESSIONAL", "subjectId", notary.id(), "reason", "Short"))))
                .isEqualTo("REASON_REQUIRED");
        MockHttpServletResponse unknown = sendJson(first.browser(), post(BASE + "/holds"), body("subjectKind", "PROFESSIONAL", "subjectId", UUID.randomUUID(),
                "reason", "Litigation notice DEMO-LIT-1 received"));
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("SUBJECT_NOT_FOUND");
        assertThat(audited("RECORD_REFUSED", null)).as("a refusal is audited on its own").isPositive();

        JsonNode hold = ok(sendJson(first.browser(), post(BASE + "/holds"), body("subjectKind", "PROFESSIONAL", "subjectId", notary.id(),
                "reason", "Litigation notice DEMO-LIT-1 received")), 201);
        String id = hold.path("id").asString("");
        assertThat(hold.path("inForce").asBoolean()).isTrue();
        assertThat(hold.path("placedBy").asString("")).isEqualTo(first.id().toString());

        // The auditor reads the hold and the evidence's retention, never the evidence itself.
        JsonNode seen = ok(auditor.browser().send(get(BASE + "/holds").param("kind", "PROFESSIONAL").param("id", notary.id().toString())), 200);
        assertThat(seen.findValuesAsString("id")).containsExactly(id);
        JsonNode record = ok(auditor.browser().send(get(BASE + "/retention").param("kind", "PROFESSIONAL_EVIDENCE").param("id", evidence.toString())), 200);
        assertThat(record.path("retentionClass").asString("")).isEqualTo("PROFESSIONAL_EVIDENCE");
        assertThat(record.path("standing").asString("")).isEqualTo("NO_APPROVED_SCHEDULE_KEPT");
        assertThat(record.path("holdsInForce").asLong()).as("the professional's hold covers their evidence").isEqualTo(1);
        assertThat(record.path("disposalAllowed").asBoolean()).isFalse();
        assertThat(record.has("storageKey")).isFalse();
        assertThat(auditor.browser().send(get("/api/v1/admin/professionals/" + notary.id())).getStatus()).as("reading retention opens nothing else")
                .isEqualTo(403);

        // Released by two: the requester never decides.
        String asked = ok(sendJson(first.browser(), post(BASE + "/holds/" + id + "/release-requests"), body("reason", "The litigation was settled")), 200)
                .path("releases").get(0).path("id").asString("");
        assertThat(code(sendJson(second.browser(), post(BASE + "/holds/" + id + "/release-requests"), body("reason", "Asking a second time over"))))
                .isEqualTo("RELEASE_PENDING");
        MockHttpServletResponse own = sendJson(first.browser(), post(BASE + "/holds/" + id + "/release-requests/" + asked + "/decision"), body("outcome", "APPROVED"));
        assertThat(own.getStatus()).isEqualTo(403);
        assertThat(code(own)).isEqualTo("REQUESTER_CANNOT_DECIDE");
        assertThat(code(sendJson(second.browser(), post(BASE + "/holds/" + id + "/release-requests/" + asked + "/decision"), body("outcome", "REJECTED"))))
                .isEqualTo("REASON_REQUIRED");
        JsonNode rejected = ok(sendJson(second.browser(), post(BASE + "/holds/" + id + "/release-requests/" + asked + "/decision"),
                body("outcome", "REJECTED", "reason", "The litigation is still open, see DEMO-LIT-1")), 200);
        assertThat(rejected.path("inForce").asBoolean()).as("a rejected release leaves the hold").isTrue();

        String again = ok(sendJson(first.browser(), post(BASE + "/holds/" + id + "/release-requests"), body("reason", "The litigation was settled, DEMO-LIT-1 closed")), 200)
                .path("releases").get(1).path("id").asString("");
        JsonNode released = ok(sendJson(second.browser(), post(BASE + "/holds/" + id + "/release-requests/" + again + "/decision"), body("outcome", "APPROVED")), 200);
        assertThat(released.path("inForce").asBoolean()).isFalse();
        assertThat(released.path("releases").findValuesAsString("status")).containsExactly("REJECTED", "APPROVED");
        assertThat(code(sendJson(first.browser(), post(BASE + "/holds/" + id + "/release-requests"), body("reason", "Releasing it once more"))))
                .isEqualTo("HOLD_RELEASED");

        JsonNode after = ok(auditor.browser().send(get(BASE + "/retention").param("kind", "PROFESSIONAL_EVIDENCE").param("id", evidence.toString())), 200);
        assertThat(after.path("holdsInForce").asLong()).isZero();
        assertThat(after.path("disposalAllowed").asBoolean()).as("no approved schedule: still kept").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legal_hold", Long.class)).as("row-level security: no actor, no rows").isZero();
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement st = su.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM legal_hold WHERE id = '" + id + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("the hold is never deleted, only released").isEqualTo(1);
        }

        for (String action : List.of("RECORD_HOLD_PLACED", "RECORD_HOLD_RELEASE_REQUESTED", "RECORD_HOLD_RELEASE_REJECTED", "RECORD_HOLD_RELEASED")) {
            assertThat(audited(action, id)).as(action).isPositive();
        }
        assertThat(jdbc.queryForObject("SELECT actor_id::text FROM audit_event WHERE action = 'RECORD_HOLD_RELEASED' AND entity_id = ?::uuid", String.class, id))
                .isEqualTo(second.id().toString());
    }
}
