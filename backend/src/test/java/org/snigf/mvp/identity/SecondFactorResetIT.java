// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR-028: a second factor is reset only when an administrator asks and the person's
 * supervisor confirms in the application how the identity was checked; requester, confirmer
 * and person are three people; the database performs the reset and refuses any other way.
 */
class SecondFactorResetIT extends IdentityIntegrationTest {

    static final String CONFIRMATIONS = "/api/v1/identity/reset-confirmations";

    @Test
    void aResetHappensOnlyWhenThePersonsSupervisorConfirms() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        SignedIn supervisor = signedIn("SUPERVISOR");
        SignedIn officer = signedIn("REVIEWER");

        String request = requested(request(admin, officer.id(), "Lost both keys and the code sheet"));
        assertThat(officer.browser().session().getStatus()).as("nothing happens before confirmation").isEqualTo(200);
        assertThat(keys(officer.id())).isEqualTo(2);
        assertThat(admin.browser().send(get("/api/v1/admin/mfa-reset-requests").param("status", "PENDING"))
                .getContentAsString()).contains(request);

        assertThat(confirm(admin, request, "I checked it myself").getStatus()).as("administrators do not confirm")
                .isEqualTo(403);
        JsonNode toConfirm = json.readTree(supervisor.browser().send(get(CONFIRMATIONS)).getContentAsString());
        assertThat(toConfirm.findValuesAsString("id")).contains(request);
        assertRefused(confirm(supervisor, request, " "), 422, "IDENTITY_CHECK_REQUIRED");

        assertThat(confirm(supervisor, request, "In person, national ID card checked").getStatus()).isEqualTo(204);

        assertThat(officer.browser().session().getStatus()).as("every session ends").isEqualTo(401);
        assertThat(new Browser().login(officer.email(), PASSWORD).getContentAsString()).contains("MFA_ENROLMENT");
        assertThat(keys(officer.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_recovery_code WHERE user_id = ? AND used_at IS NULL"
                + " AND invalidated_at IS NULL", Long.class, officer.id())).isZero();
        assertThat(jdbc.queryForList("SELECT action || ':' || outcome || ':' || actor_id FROM audit_event"
                + " WHERE entity_id = ?::uuid ORDER BY chain_pos", String.class, request)).containsExactly(
                "MFA_RESET_REQUESTED:SUCCESS:" + admin.id(), "MFA_RESET:SUCCESS:" + supervisor.id());
        JsonNode details = json.readTree(jdbc.queryForObject("SELECT details::text FROM audit_event"
                + " WHERE action = 'MFA_RESET' AND entity_id = ?::uuid", String.class, request));
        assertThat(details.path("identityCheck").asString("")).isEqualTo("In person, national ID card checked");
        assertThat(details.path("requestedBy").asString("")).isEqualTo(admin.id().toString());
        assertThat(details.path("authenticatorsRemoved").asInt()).isEqualTo(2);
        assertThat(details.path("sessionsRevoked").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void requesterPersonAndSupervisorsOfOtherOfficesNeverConfirm() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        SignedIn supervisor = signedIn("SUPERVISOR");
        SignedIn elsewhere = signedIn("SUPERVISOR", "DEMOIT2");
        SignedIn officer = signedIn("REVIEWER");

        assertRefused(request(admin, admin.id(), "Resetting my own keys"), 403, "SOD_SELF_ACTION");
        assertRefused(request(admin, officer.id(), "too short"), 422, "REASON_REQUIRED");
        String applicant = email();
        new Browser().register(applicant, "Applicant Only", PASSWORD);
        assertRefused(request(admin, userId(applicant), "Applicants have no second factor"), 422, "ACCOUNT_NOT_STAFF");

        String request = requested(request(admin, officer.id(), "Lost both keys and the code sheet"));
        assertRefused(request(admin, officer.id(), "Asked twice by mistake"), 409, "REQUEST_ALREADY_OPEN");

        assertThat(elsewhere.browser().send(get(CONFIRMATIONS)).getContentAsString()).doesNotContain(request);
        assertRefused(confirm(elsewhere, request, "Over the phone"), 404, "REQUEST_NOT_FOUND");

        String own = requested(request(admin, supervisor.id(), "The supervisor lost their keys"));
        assertRefused(confirm(supervisor, own, "I am myself"), 403, "SOD_FOUR_EYES");

        assertThat(sendJson(supervisor.browser(), post(CONFIRMATIONS + "/" + request + "/reject"),
                "{\"reason\":\"The person still has a key\"}").getStatus()).isEqualTo(204);
        assertThat(keys(officer.id())).as("a rejection changes nothing").isEqualTo(2);
        assertRefused(confirm(supervisor, request, "Too late"), 409, "REQUEST_NOT_PENDING");

        String old = requested(request(admin, officer.id(), "Lost both keys again, later"));
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement statement = su.createStatement()) {
            statement.execute("UPDATE mfa_reset_request SET requested_at = now() - interval '8 days' WHERE id = '"
                    + UUID.fromString(old) + "'");
            su.commit();
        }
        assertRefused(confirm(supervisor, old, "In person"), 409, "REQUEST_EXPIRED");
    }

    @Test
    void theApplicationsDatabaseAccountCannotResetOrStripTheLastKey() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");

        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET mfa_enrolled_at = NULL WHERE id = ?", officer.id()))
                .rootCause().hasMessageContaining("reset only through a confirmed reset request");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_credentials c USING user_entities e"
                + " WHERE c.user_entity_user_id = e.id AND e.app_user_id = ?", officer.id()))
                .rootCause().hasMessageContaining("the last key of an enrolled account cannot be removed");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO mfa_reset_request (id, user_id, reason, requested_by, status,"
                + " decided_by, decided_at, identity_check) VALUES (?, ?, 'forged confirmation', ?, 'CONFIRMED', ?, now(),"
                + " 'none')", UUID.randomUUID(), officer.id(), admin.id(), admin.id()))
                .rootCause().hasMessageContaining("a request is created pending");
        String request = requested(request(admin, officer.id(), "Lost both keys and the code sheet"));
        assertThatThrownBy(() -> jdbc.update("UPDATE mfa_reset_request SET status = 'CONFIRMED', decided_by = ?,"
                + " decided_at = now(), identity_check = 'forged' WHERE id = ?::uuid", admin.id(), request))
                .rootCause().hasMessageContaining("requires holding identity.reset.confirm");
        assertThat(keys(officer.id())).isEqualTo(2);
    }

    private MockHttpServletResponse request(SignedIn admin, UUID user, String reason) throws Exception {
        return sendJson(admin.browser(), post("/api/v1/admin/users/" + user + "/mfa-reset"),
                "{\"reason\":\"" + reason + "\"}");
    }

    private MockHttpServletResponse confirm(SignedIn who, String request, String identityCheck) throws Exception {
        return sendJson(who.browser(), post(CONFIRMATIONS + "/" + request + "/confirm"),
                "{\"identityCheck\":\"" + identityCheck + "\"}");
    }

    private String requested(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
        return json.readTree(response.getContentAsString()).path("requestId").asString("");
    }

    private long keys(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM user_credentials c JOIN user_entities e"
                + " ON e.id = c.user_entity_user_id WHERE e.app_user_id = ?", Long.class, user);
    }

    private static void assertRefused(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains(code);
    }
}
