// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security step 4d (ADR-025): administrators define custom roles, always with a second
 * administrator's approval; baseline roles never change; separation of duties holds.
 */
class RoleAdministrationIT extends IdentityIntegrationTest {

    static final String REQUESTS = "/api/v1/admin/role-requests";

    @Test
    void aCustomRoleExistsOnlyOnceASecondAdministratorApprovesThenOpensExactlyItsEndpoints() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        SignedIn officer = signedIn("REVIEWER");
        String role = code("KEYS");
        assertThat(officer.browser().send(get("/api/v1/audit/checkpoint-keys")).getStatus()).isEqualTo(403);

        String request = created(propose(requester, "CREATE", role, "{\"audit.key.read\":\"ANY\"}"), "requestId");

        assertRefused(decide(requester, request, "approve"), 403, "SOD_FOUR_EYES");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_definition WHERE code = ?", Long.class, role)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'ROLE_DEFINITION_APPROVED'"
                + " AND outcome = 'DENIED' AND entity_id = ?::uuid", Long.class, request)).isEqualTo(1);

        assertThat(decide(approver, request, "approve").getStatus()).isEqualTo(204);
        assertThat(approver.browser().send(get("/api/v1/admin/roles")).getContentAsString()).contains(role);

        assign(requester, approver, officer.id(), role);
        assertThat(officer.browser().send(get("/api/v1/audit/checkpoint-keys")).getStatus()).isEqualTo(200);
        assertThat(officer.browser().send(get("/api/v1/audit/checkpoints")).getStatus()).as("only what the role holds")
                .isEqualTo(403);

        assertThat(jdbc.queryForList("SELECT action || ':' || actor_id FROM audit_event WHERE entity_id = ?::uuid"
                + " AND outcome = 'SUCCESS' ORDER BY chain_pos", String.class, request))
                .containsExactly("ROLE_DEFINITION_REQUESTED:" + requester.id(), "ROLE_DEFINITION_APPROVED:" + approver.id());
    }

    @Test
    void baselineRolesNeverChangeAndProposalsFollowTheCatalogue() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");

        assertRefused(propose(admin, "UPDATE", "SYSTEM_ADMIN", "{\"parcel.read\":\"ANY\"}"), 403, "SYSTEM_ROLE_IMMUTABLE");
        assertRefused(propose(admin, "RETIRE", "AUDITOR", null), 403, "SYSTEM_ROLE_IMMUTABLE");
        assertRefused(propose(admin, "CREATE", "AUDITOR", "{\"parcel.read\":\"ANY\"}"), 409, "ROLE_EXISTS");
        assertRefused(propose(admin, "UPDATE", code("NONE"), "{\"parcel.read\":\"ANY\"}"), 404, "ROLE_NOT_FOUND");
        assertRefused(propose(admin, "CREATE", "lower_case", "{\"parcel.read\":\"ANY\"}"), 422, "ROLE_CODE_INVALID");
        assertRefused(propose(admin, "CREATE", code("EMPTY"), "{}"), 422, "ROLE_WITHOUT_PERMISSION");
        assertRefused(propose(admin, "CREATE", code("FLY"), "{\"parcel.fly\":\"ANY\"}"), 422, "PERMISSION_UNKNOWN");
        assertRefused(propose(admin, "CREATE", code("SCOPE"), "{\"payment.reconcile\":\"OFFICE\"}"), 422, "SCOPE_NOT_ALLOWED");
        assertRefused(propose(admin, "CREATE", code("BOTH"), "{\"admin.account.list\":\"ANY\",\"audit.event.search\":\"ANY\"}"),
                409, "SOD_CONFLICT");
        assertRefused(sendJson(admin.browser(), post(REQUESTS), "{\"kind\":\"CREATE\",\"roleCode\":\"" + code("WHY")
                + "\",\"nameFr\":\"Rôle\",\"nameEn\":\"Role\",\"permissions\":{\"parcel.read\":\"ANY\"},\"reason\":\"short\"}"),
                422, "REASON_REQUIRED");

        String role = code("TWICE");
        assertThat(propose(admin, "CREATE", role, "{\"parcel.read\":\"ANY\"}").getStatus()).isEqualTo(201);
        assertRefused(propose(admin, "CREATE", role, "{\"parcel.read\":\"ANY\"}"), 409, "REQUEST_ALREADY_OPEN");
    }

    @Test
    void nobodyApprovesAChangeThatBenefitsThemOrMixesDutiesAndHeldRolesStay() throws Exception {
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn holder = signedIn("SYSTEM_ADMIN");
        SignedIn third = signedIn("SYSTEM_ADMIN");
        String role = code("READER");
        assertThat(decide(holder, created(propose(first, "CREATE", role, "{\"parcel.read\":\"ANY\"}"), "requestId"),
                "approve").getStatus()).isEqualTo(204);
        String assignment = assign(first, third, holder.id(), role);

        String widen = created(propose(first, "UPDATE", role, "{\"parcel.read\":\"ANY\",\"audit.key.read\":\"ANY\"}"),
                "requestId");
        assertRefused(decide(holder, widen, "approve"), 403, "SOD_SELF_BENEFIT");
        assertRefused(decide(third, widen, "approve"), 409, "SOD_CONFLICT");
        assertThat(decide(third, widen, "reject").getStatus()).isEqualTo(204);
        assertRefused(decide(third, widen, "approve"), 409, "REQUEST_NOT_PENDING");

        String retire = created(propose(first, "RETIRE", role, null), "requestId");
        assertRefused(decide(third, retire, "approve"), 409, "ROLE_IN_USE");
        assertThat(sendJson(first.browser(), delete("/api/v1/admin/users/" + holder.id() + "/roles/" + assignment),
                "{\"reason\":\"No longer needed\"}").getStatus()).isEqualTo(204);
        assertThat(decide(third, retire, "approve").getStatus()).isEqualTo(204);
        assertRefused(requestRole(first, holder.id(), role), 422, "ROLE_NOT_ASSIGNABLE");
    }

    @Test
    void anOldRequestCanNoLongerBeApproved() throws Exception {
        SignedIn requester = signedIn("SYSTEM_ADMIN");
        SignedIn approver = signedIn("SYSTEM_ADMIN");
        String request = created(propose(requester, "CREATE", code("OLD"), "{\"parcel.read\":\"ANY\"}"), "requestId");
        try (Connection su = PostgisDatabase.shared().superuserConnection(); Statement statement = su.createStatement()) {
            statement.execute("UPDATE role_change_request SET requested_at = now() - interval '8 days' WHERE id = '"
                    + UUID.fromString(request) + "'");
            su.commit();
        }
        assertRefused(decide(approver, request, "approve"), 409, "REQUEST_EXPIRED");
    }

    @Test
    void onlyAdministratorsReadOrDefineRoles() throws Exception {
        SignedIn officer = signedIn("REVIEWER");
        assertThat(officer.browser().send(get("/api/v1/admin/roles")).getStatus()).isEqualTo(403);
        assertThat(officer.browser().send(get("/api/v1/admin/permissions")).getStatus()).isEqualTo(403);
        assertThat(propose(officer, "CREATE", code("MINE"), "{\"parcel.read\":\"ANY\"}").getStatus()).isEqualTo(403);
    }

    static String code(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private MockHttpServletResponse propose(SignedIn admin, String kind, String role, String permissions) throws Exception {
        String body = "{\"kind\":\"" + kind + "\",\"roleCode\":\"" + role + "\",\"nameFr\":\"Rôle de test\","
                + "\"nameEn\":\"Test role\"," + (permissions == null ? "" : "\"permissions\":" + permissions + ",")
                + "\"reason\":\"Needed for the integration test\"}";
        return sendJson(admin.browser(), post(REQUESTS), body);
    }

    private MockHttpServletResponse decide(SignedIn admin, String request, String decision) throws Exception {
        return sendJson(admin.browser(), post(REQUESTS + "/" + request + "/" + decision), "{\"reason\":\"Reviewed\"}");
    }

    private MockHttpServletResponse requestRole(SignedIn admin, UUID user, String role) throws Exception {
        return sendJson(admin.browser(), post("/api/v1/admin/users/" + user + "/roles"),
                "{\"role\":\"" + role + "\",\"officeCode\":\"DEMOIT\",\"reason\":\"Test assignment\"}");
    }

    /** Requested by one administrator, approved by another (ADR-026); returns the assignment id. */
    private String assign(SignedIn requester, SignedIn approver, UUID user, String role) throws Exception {
        MockHttpServletResponse requested = requestRole(requester, user, role);
        assertThat(requested.getStatus()).as(requested.getContentAsString()).isEqualTo(202);
        String id = json.readTree(requested.getContentAsString()).path("requestId").asString("");
        MockHttpServletResponse approved = sendJson(approver.browser(),
                post("/api/v1/admin/assignment-requests/" + id + "/approve"), "{\"reason\":\"Reviewed\"}");
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(204);
        return id;
    }

    private String created(MockHttpServletResponse response, String field) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return json.readTree(response.getContentAsString()).path(field).asString("");
    }

    private static void assertRefused(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains(code);
    }
}
