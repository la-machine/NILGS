// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Role administration with separation of duties (C6c), four eyes on every assignment and the
 * database refusing what a compromised application might try (ADR-026), through the real
 * endpoints. The reset of a lost second factor is {@link SecondFactorResetIT}.
 */
class AdminFlowIT extends IdentityIntegrationTest {

    static final String ADMIN = "/api/v1/admin/users";

    @Test
    void aRoleExistsOnlyOnceASecondAdministratorApprovesItAndTakesEffectAtOnce() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");
        assertThat(permissions(officer)).contains("parcel.read").doesNotContain("parcel.create@DEMOIT");

        MockHttpServletResponse requested = request(requester, officer.id(), "CADASTRAL_OFFICER", "DEMOIT",
                "Transferred to the cadastral unit");
        assertThat(requested.getStatus()).as(requested.getContentAsString()).isEqualTo(202);
        String request = json.readTree(requested.getContentAsString()).path("requestId").asString("");
        assertThat(permissions(officer)).as("nothing granted yet").doesNotContain("parcel.create@DEMOIT");

        assertRefused(decide(requester, request, "approve"), 403, "SOD_FOUR_EYES");
        assertThat(decide(officer, request, "approve").getStatus()).as("not an administrator").isEqualTo(403);
        assertThat(decide(approver, request, "approve").getStatus()).isEqualTo(204);
        assertThat(permissions(officer)).as("the next request sees it").contains("parcel.create@DEMOIT");
        assertRefused(decide(approver, request, "approve"), 409, "REQUEST_NOT_PENDING");

        MockHttpServletResponse revoked = sendJson(requester.browser(),
                delete(ADMIN + "/" + officer.id() + "/roles/" + request), "{\"reason\":\"Back to review\"}");
        assertThat(revoked.getStatus()).as("the assignment carries the request's id").isEqualTo(204);
        assertThat(permissions(officer)).doesNotContain("parcel.create@DEMOIT");

        assertThat(jdbc.queryForList("""
                SELECT action || ':' || outcome || ':' || actor_id FROM audit_event
                 WHERE entity_id = ?::uuid ORDER BY chain_pos""", String.class, request))
                .containsExactly("ROLE_ASSIGNMENT_REQUESTED:SUCCESS:" + requester.id(),
                        "ROLE_GRANTED:DENIED:" + requester.id(),
                        "ROLE_GRANTED:SUCCESS:" + approver.id(),
                        "ROLE_REVOKED:SUCCESS:" + requester.id());
    }

    @Test
    void nobodyGivesThemselvesARoleNotEvenThroughTheApplicationsDatabaseAccount() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        SignedIn other = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");

        assertRefused(request(admin, admin.id(), "REVIEWER", "DEMOIT", "I would like to review"), 403, "SOD_SELF_ASSIGNMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'ROLE_ASSIGNMENT_REQUESTED'"
                + " AND outcome = 'DENIED' AND actor_id = ?", Long.class, admin.id())).isEqualTo(1);

        String forOther = json.readTree(request(admin, other.id(), "REVIEWER", "DEMOIT", "Second job")
                .getContentAsString()).path("requestId").asString("");
        assertRefused(decide(other, forOther, "approve"), 403, "SOD_FOUR_EYES");

        // What a compromised application could try with its own database account (V8).
        UUID office = jdbc.queryForObject("SELECT id FROM office WHERE code = 'DEMOIT'", UUID.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO role_assignment (id, user_id, role, office_id, granted_by,"
                + " grant_reason) VALUES (?, ?, 'CADASTRAL_OFFICER', ?, ?, 'direct')", UUID.randomUUID(), officer.id(),
                office, admin.id()))
                .rootCause().hasMessageContaining("a role assignment requires an approved request");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO role_assignment_request (id, user_id, role, office_id, reason,"
                + " requested_by, status, decided_by, decided_at) VALUES (?, ?, 'CADASTRAL_OFFICER', ?, 'forged', ?,"
                + " 'APPROVED', ?, now())", UUID.randomUUID(), officer.id(), office, admin.id(), other.id()))
                .rootCause().hasMessageContaining("a request is created pending");
        assertThatThrownBy(() -> jdbc.update("UPDATE role_assignment_request SET status = 'APPROVED', decided_by = ?,"
                + " decided_at = now() WHERE id = ?::uuid", officer.id(), forOther))
                .rootCause().hasMessageContaining("requires an account holding admin.assignment.approve");
    }

    @Test
    void requestsFollowTheRules() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");

        assertRefused(request(admin, officer.id(), "REVIEWER", "DEMOIT", "Already has it"), 409, "ROLE_ALREADY_ACTIVE");
        assertRefused(request(admin, officer.id(), "APPLICANT", "DEMOIT", "Wrong role"), 422, "ROLE_NOT_ASSIGNABLE");
        assertRefused(request(admin, officer.id(), "WIZARD", "DEMOIT", "Unknown role"), 422, "ROLE_NOT_ASSIGNABLE");
        assertRefused(request(admin, officer.id(), "AUDITOR", "NOWHERE", "Unknown office"), 404, "OFFICE_NOT_FOUND");
        assertRefused(request(admin, officer.id(), "AUDITOR", "DEMOIT", " "), 422, "REASON_REQUIRED");
        assertRefused(request(admin, UUID.randomUUID(), "AUDITOR", "DEMOIT", "Nobody"), 404, "USER_NOT_FOUND");
        assertRefused(request(admin, approver.id(), "AUDITOR", "DEMOIT", "Administrator and auditor"), 409, "SOD_CONFLICT");

        String applicant = email();
        new Browser().register(applicant, "Applicant Only", PASSWORD);
        assertRefused(request(admin, userId(applicant), "AUDITOR", "DEMOIT", "Applicants never get roles"),
                422, "ACCOUNT_NOT_STAFF");

        String open = json.readTree(request(admin, officer.id(), "INTAKE_OFFICER", "DEMOIT", "Front desk")
                .getContentAsString()).path("requestId").asString("");
        assertRefused(request(admin, officer.id(), "INTAKE_OFFICER", "DEMOIT", "Front desk again"), 409,
                "REQUEST_ALREADY_OPEN");
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.Statement statement = su.createStatement()) {
            statement.execute("UPDATE role_assignment_request SET requested_at = now() - interval '8 days' WHERE id = '"
                    + UUID.fromString(open) + "'");
            su.commit();
        }
        assertRefused(decide(approver, open, "approve"), 409, "REQUEST_EXPIRED");
        assertThat(decide(approver, open, "reject").getStatus()).isEqualTo(204);
        assertThat(approver.browser().send(get("/api/v1/admin/assignment-requests").param("status", "REJECTED"))
                .getContentAsString()).contains(open);
    }

    @Test
    void theDirectoryFindsAccountsWithoutPatternTricks() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");
        String part = officer.email().substring(5, 20);

        MockHttpServletResponse found = admin.browser().send(get(ADMIN).param("q", part));
        assertThat(found.getStatus()).isEqualTo(200);
        JsonNode accounts = json.readTree(found.getContentAsString());
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).path("secondFactorReady").asBoolean()).isTrue();
        assertThat(accounts.get(0).path("assignments").get(0).path("role").asString("")).isEqualTo("REVIEWER");

        assertThat(json.readTree(admin.browser().send(get(ADMIN).param("q", "%")).getContentAsString()))
                .as("% is text, not a wildcard").isEmpty();
    }

    @Test
    void onlyAdministratorsAdminister() throws Exception {
        SignedIn officer = signedIn("REVIEWER");
        assertThat(officer.browser().send(get(ADMIN)).getStatus()).isEqualTo(403);
        assertThat(request(officer, officer.id(), "AUDITOR", "DEMOIT", "Trying").getStatus()).isEqualTo(403);
        assertThat(officer.browser().send(get("/api/v1/admin/assignment-requests")).getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse request(SignedIn admin, UUID user, String role, String office, String reason)
            throws Exception {
        return sendJson(admin.browser(), post(ADMIN + "/" + user + "/roles"),
                "{\"role\":\"" + role + "\",\"officeCode\":\"" + office + "\",\"reason\":\"" + reason + "\"}");
    }

    private MockHttpServletResponse decide(SignedIn admin, String request, String decision) throws Exception {
        return sendJson(admin.browser(), post("/api/v1/admin/assignment-requests/" + request + "/" + decision),
                "{\"reason\":\"Reviewed\"}");
    }

    private java.util.List<String> permissions(SignedIn user) throws Exception {
        java.util.List<String> codes = new java.util.ArrayList<>();
        json.readTree(user.browser().session().getContentAsString()).path("permissions")
                .forEach(p -> codes.add(p.asString("")));
        return codes;
    }

    private static void assertRefused(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains(code);
    }
}
