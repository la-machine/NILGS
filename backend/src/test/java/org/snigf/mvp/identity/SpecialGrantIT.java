// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.application.SpecialGrantService;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security step 4d (ADR-025): special grants of one permission to one person, approved by a
 * second administrator, limited by time and/or uses, every use counted and audited.
 */
class SpecialGrantIT extends IdentityIntegrationTest {

    static final String GRANTS = "/api/v1/admin/grants";
    static final String KEYS = "/api/v1/audit/checkpoint-keys";

    @Autowired
    SpecialGrantService specialGrants;

    @Test
    void aGrantOfTwoUsesAllowsTwoActionsEachAudited() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");

        String grant = created(request(requester, officer.id(), "audit.key.read", null, null, 2));
        assertThat(officer.browser().send(get(KEYS)).getStatus()).as("not before approval").isEqualTo(403);
        assertRefused(decide(requester, grant, "approve"), 403, "SOD_FOUR_EYES");
        assertThat(decide(approver, grant, "approve").getStatus()).isEqualTo(204);

        JsonNode mine = json.readTree(officer.browser().session().getContentAsString()).path("specialGrants");
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("permission").asString("")).isEqualTo("audit.key.read");
        assertThat(mine.get(0).path("usesLeft").asInt()).isEqualTo(2);

        assertThat(officer.browser().send(get(KEYS)).getStatus()).isEqualTo(200);
        assertThat(officer.browser().send(get(KEYS)).getStatus()).isEqualTo(200);
        assertThat(officer.browser().send(get(KEYS)).getStatus()).as("uses exhausted").isEqualTo(403);
        assertThat(officer.browser().send(get("/api/v1/audit/checkpoints")).getStatus()).as("only that permission")
                .isEqualTo(403);

        assertThat(jdbc.queryForObject("SELECT uses FROM permission_grant WHERE id = ?::uuid", Integer.class, grant))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT action || ':' || outcome || ':' || coalesce(actor_id::text, '') FROM audit_event"
                + " WHERE entity_id = ?::uuid ORDER BY chain_pos", String.class, grant)).containsExactly(
                "PERMISSION_GRANT_REQUESTED:SUCCESS:" + requester.id(),
                "PERMISSION_GRANT_APPROVED:DENIED:" + requester.id(),
                "PERMISSION_GRANT_APPROVED:SUCCESS:" + approver.id(),
                "PERMISSION_GRANT_USED:SUCCESS:" + officer.id(),
                "PERMISSION_GRANT_USED:SUCCESS:" + officer.id());
    }

    @Test
    void aFailedActionGivesTheUseBack() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");
        String grant = approved(requester, approver, officer.id(), "audit.checkpoint.download", null, 1);

        assertThat(officer.browser().send(get("/api/v1/audit/checkpoints/999999999/file")).getStatus()).isEqualTo(404);

        assertThat(jdbc.queryForObject("SELECT uses FROM permission_grant WHERE id = ?::uuid", Integer.class, grant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ?::uuid"
                + " AND action = 'PERMISSION_GRANT_USED' AND outcome = 'FAILED'", Long.class, grant)).isEqualTo(1);
        JsonNode mine = json.readTree(officer.browser().session().getContentAsString()).path("specialGrants");
        assertThat(mine.get(0).path("usesLeft").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentUsesNeverExceedTheLimit() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");
        UUID grant = UUID.fromString(approved(requester, approver, officer.id(), "audit.key.read", null, 3));

        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            attempts.add(() -> specialGrants.reserve(grant));
        }
        int taken = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<Boolean> result : pool.invokeAll(attempts)) {
                taken += result.get() ? 1 : 0;
            }
        }
        assertThat(taken).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT uses FROM permission_grant WHERE id = ?", Integer.class, grant)).isEqualTo(3);
    }

    @Test
    void aGrantEndsAtItsEndDateOrWhenRevoked() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");

        String timed = approved(requester, approver, officer.id(), "audit.key.read", Instant.now().plus(Duration.ofHours(1)), null);
        assertThat(officer.browser().send(get(KEYS)).getStatus()).isEqualTo(200);
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement statement = su.createStatement()) {
            statement.execute("UPDATE permission_grant SET requested_at = now() - interval '2 days',"
                    + " valid_until = now() - interval '1 day' WHERE id = '" + UUID.fromString(timed) + "'");
            su.commit();
        }
        assertThat(officer.browser().send(get(KEYS)).getStatus()).as("past its end date").isEqualTo(403);

        String open = approved(requester, approver, officer.id(), "audit.key.read", null, null);
        assertThat(officer.browser().send(get(KEYS)).getStatus()).as("no limit").isEqualTo(200);
        assertThat(officer.browser().send(get(KEYS)).getStatus()).isEqualTo(200);
        assertThat(sendJson(approver.browser(), post(GRANTS + "/" + open + "/revoke"), "{\"reason\":\"Task done\"}")
                .getStatus()).isEqualTo(204);
        assertThat(officer.browser().send(get(KEYS)).getStatus()).as("revoked").isEqualTo(403);
        assertRefused(sendJson(approver.browser(), post(GRANTS + "/" + open + "/revoke"), "{\"reason\":\"Again\"}"),
                409, "GRANT_NOT_ACTIVE");
    }

    @Test
    void grantsFollowTheRules() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn other = signedIn("SYSTEM_ADMIN");
        SignedIn third = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");

        assertRefused(request(requester, requester.id(), "audit.key.read", null, null, null), 403, "SOD_SELF_ASSIGNMENT");
        assertRefused(request(requester, officer.id(), "admin.role.assign", null, null, null), 422, "PERMISSION_NOT_DELEGABLE");
        assertRefused(request(requester, officer.id(), "parcel.fly", null, null, null), 422, "PERMISSION_UNKNOWN");
        assertRefused(request(requester, officer.id(), "payment.reconcile", "DEMOIT", null, null), 422, "SCOPE_NOT_ALLOWED");
        assertRefused(request(requester, officer.id(), "parcel.create", "NOWHERE", null, null), 404, "OFFICE_NOT_FOUND");
        assertRefused(request(requester, officer.id(), "audit.key.read", null, Instant.now().minusSeconds(60), null),
                422, "VALID_UNTIL_IN_PAST");
        assertRefused(request(requester, officer.id(), "audit.key.read", null, null, 0), 422, "MAX_USES_INVALID");
        String applicant = email();
        new Browser().register(applicant, "Applicant Only", PASSWORD);
        assertRefused(request(requester, userId(applicant), "audit.key.read", null, null, null), 422, "ACCOUNT_NOT_STAFF");

        String forOther = created(request(requester, other.id(), "audit.key.read", null, null, 1));
        assertRefused(decide(other, forOther, "approve"), 403, "SOD_FOUR_EYES");
        assertRefused(decide(third, forOther, "approve"), 409, "SOD_CONFLICT");

        assertThat(officer.browser().send(get(GRANTS)).getStatus()).as("only administrators").isEqualTo(403);
        assertThat(request(officer, other.id(), "parcel.read", null, null, null).getStatus()).isEqualTo(403);
    }

    private String approved(SignedIn requester, SignedIn approver, UUID user, String permission, Instant validUntil,
                            Integer maxUses) throws Exception {
        String grant = created(request(requester, user, permission, null, validUntil, maxUses));
        assertThat(decide(approver, grant, "approve").getStatus()).isEqualTo(204);
        return grant;
    }

    private MockHttpServletResponse request(SignedIn admin, UUID user, String permission, String office,
                                            Instant validUntil, Integer maxUses) throws Exception {
        return sendJson(admin.browser(), post(GRANTS), "{\"userId\":\"" + user + "\",\"permission\":\"" + permission + "\","
                + (office == null ? "" : "\"officeCode\":\"" + office + "\",")
                + (validUntil == null ? "" : "\"validUntil\":\"" + validUntil + "\",")
                + (maxUses == null ? "" : "\"maxUses\":" + maxUses + ",")
                + "\"reason\":\"Needed for the integration test\"}");
    }

    private MockHttpServletResponse decide(SignedIn admin, String grant, String decision) throws Exception {
        return sendJson(admin.browser(), post(GRANTS + "/" + grant + "/" + decision), "{\"reason\":\"Reviewed\"}");
    }

    private String created(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).path("grantId").asString("");
    }

    private static void assertRefused(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains(code);
    }
}
