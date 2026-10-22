// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.snigf.mvp.identity.domain.BaselineRole;
import org.snigf.mvp.shared.security.Permission;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Slice 1 design (S1-AC4): every role against every protected endpoint of the slice, as
 * the permission matrix says. A role either passes the access check (any status but 403)
 * or is refused with 403, for each endpoint; the expectation is computed from
 * {@link BaselineRole}, so the matrix, the code and the endpoints cannot drift apart.
 */
class PermissionMatrixIT extends IdentityIntegrationTest {

    record Endpoint(String name, Permission permission, Request request) {
    }

    @FunctionalInterface
    interface Request {
        MockHttpServletRequestBuilder build();
    }

    static final UUID SOMEONE = UUID.fromString("00000000-0000-7000-8000-00000000beef");

    static final String JSON = "application/json";
    static final String DECISION = "{\"reason\":\"permission matrix\"}";

    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("GET /admin/users", Permission.ADMIN_ACCOUNT_LIST, () -> get("/api/v1/admin/users")),
            new Endpoint("POST /admin/users/{id}/roles", Permission.ADMIN_ROLE_ASSIGN, () -> post("/api/v1/admin/users/" + SOMEONE + "/roles")
                    .contentType(JSON).content("{\"role\":\"AUDITOR\",\"officeCode\":\"DEMOIT\",\"reason\":\"matrix\"}")),
            new Endpoint("DELETE /admin/users/{id}/roles/{a}", Permission.ADMIN_ROLE_REVOKE, () -> delete("/api/v1/admin/users/" + SOMEONE + "/roles/" + SOMEONE)
                    .contentType(JSON).content(DECISION)),
            new Endpoint("POST /admin/users/{id}/mfa-reset", Permission.ADMIN_MFA_RESET, () -> post("/api/v1/admin/users/" + SOMEONE + "/mfa-reset")
                    .contentType(JSON).content("{\"reason\":\"permission matrix test\",\"confirmedBy\":\"matrix\"}")),
            new Endpoint("GET /admin/assignment-requests", Permission.ADMIN_ASSIGNMENT_READ, () -> get("/api/v1/admin/assignment-requests")),
            new Endpoint("POST /admin/assignment-requests/{id}/approve", Permission.ADMIN_ASSIGNMENT_APPROVE, () -> post("/api/v1/admin/assignment-requests/" + SOMEONE + "/approve")
                    .contentType(JSON).content(DECISION)),
            new Endpoint("POST /admin/assignment-requests/{id}/reject", Permission.ADMIN_ASSIGNMENT_APPROVE, () -> post("/api/v1/admin/assignment-requests/" + SOMEONE + "/reject")
                    .contentType(JSON).content(DECISION)),
            new Endpoint("GET /admin/permissions", Permission.ADMIN_ROLE_READ, () -> get("/api/v1/admin/permissions")),
            new Endpoint("GET /admin/roles", Permission.ADMIN_ROLE_READ, () -> get("/api/v1/admin/roles")),
            new Endpoint("GET /admin/role-requests", Permission.ADMIN_ROLE_READ, () -> get("/api/v1/admin/role-requests")),
            new Endpoint("POST /admin/role-requests", Permission.ADMIN_ROLE_DEFINE, () -> post("/api/v1/admin/role-requests")
                    .contentType(JSON).content("{\"kind\":\"RETIRE\",\"roleCode\":\"NO_SUCH_ROLE\",\"reason\":\"permission matrix\"}")),
            new Endpoint("POST /admin/role-requests/{id}/approve", Permission.ADMIN_ROLE_APPROVE, () -> post("/api/v1/admin/role-requests/" + SOMEONE + "/approve")
                    .contentType(JSON).content(DECISION)),
            new Endpoint("POST /admin/role-requests/{id}/reject", Permission.ADMIN_ROLE_APPROVE, () -> post("/api/v1/admin/role-requests/" + SOMEONE + "/reject")
                    .contentType(JSON).content(DECISION)),
            new Endpoint("GET /admin/grants", Permission.ADMIN_GRANT_READ, () -> get("/api/v1/admin/grants")),
            new Endpoint("POST /admin/grants", Permission.ADMIN_GRANT_REQUEST, () -> post("/api/v1/admin/grants")
                    .contentType(JSON).content("{\"userId\":\"" + SOMEONE + "\",\"permission\":\"parcel.read\",\"reason\":\"permission matrix\"}")),
            new Endpoint("POST /admin/grants/{id}/approve", Permission.ADMIN_GRANT_APPROVE, () -> post("/api/v1/admin/grants/" + SOMEONE + "/approve")
                    .contentType(JSON).content(DECISION)),
            new Endpoint("POST /admin/grants/{id}/reject", Permission.ADMIN_GRANT_APPROVE, () -> post("/api/v1/admin/grants/" + SOMEONE + "/reject")
                    .contentType(JSON).content(DECISION)),
            new Endpoint("POST /admin/grants/{id}/revoke", Permission.ADMIN_GRANT_REVOKE, () -> post("/api/v1/admin/grants/" + SOMEONE + "/revoke")
                    .contentType(JSON).content(DECISION)),
            new Endpoint("GET /audit/events", Permission.AUDIT_EVENT_SEARCH, () -> get("/api/v1/audit/events")),
            new Endpoint("POST /audit/verify", Permission.AUDIT_CHAIN_VERIFY, () -> post("/api/v1/audit/verify")
                    .contentType(JSON).content("{\"fromPos\":1,\"toPos\":1}")),
            new Endpoint("GET /audit/checkpoints", Permission.AUDIT_CHECKPOINT_LIST, () -> get("/api/v1/audit/checkpoints")),
            new Endpoint("GET /audit/checkpoints/{pos}/file", Permission.AUDIT_CHECKPOINT_DOWNLOAD, () -> get("/api/v1/audit/checkpoints/999999999/file")),
            new Endpoint("GET /audit/checkpoint-keys", Permission.AUDIT_KEY_READ, () -> get("/api/v1/audit/checkpoint-keys")));

    @Test
    void everyStaffRoleGetsExactlyWhatTheMatrixGrantsIt() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (BaselineRole role : BaselineRole.values()) {
            if (role == BaselineRole.APPLICANT) {
                continue;
            }
            SignedIn user = signedIn(role.name());
            for (Endpoint endpoint : ENDPOINTS) {
                boolean expected = role.permissions().get(endpoint.permission()) == Permission.Scope.ANY;
                user.browser().csrf();
                MockHttpServletResponse response = user.browser().send(endpoint.request().build());
                boolean allowed = response.getStatus() != 403;
                if (allowed != expected) {
                    mismatches.add(role + " " + endpoint.name() + ": expected " + (expected ? "allowed" : "403")
                            + ", got " + response.getStatus());
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void applicantsReachNoneOfThem() throws Exception {
        String email = email();
        Browser applicant = new Browser();
        applicant.register(email, "Matrix Applicant", PASSWORD);
        applicant.login(email, PASSWORD);
        for (Endpoint endpoint : ENDPOINTS) {
            applicant.csrf();
            assertThat(applicant.send(endpoint.request().build()).getStatus()).as(endpoint.name()).isEqualTo(403);
        }
    }
}
