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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Slice 1 design (S1-AC4): every role against every protected endpoint of the slice, as
 * the permission matrix says. A role either passes the access check (any status but 403)
 * or is refused with 403, for each endpoint; the expectation is computed from
 * {@link BaselineRole}, so the matrix, the code and the endpoints cannot drift apart.
 */
class PermissionMatrixIT extends IdentityIntegrationTest {

    /**
     * {@code reach}: EVERYWHERE needs the permission at scope any; SOMEWHERE opens for it at any
     * office (the service then narrows it); TEST_OFFICE for it everywhere or at the test office
     * the request names, where every signed-in role is assigned; OWN for the caller's own
     * records only (an applicant's applications), held by no staff role.
     */
    record Endpoint(String name, Permission permission, Request request, Reach reach) {

        Endpoint(String name, Permission permission, Request request) {
            this(name, permission, request, Reach.EVERYWHERE);
        }

        Endpoint(String name, Permission permission, Request request, boolean somewhere) {
            this(name, permission, request, somewhere ? Reach.SOMEWHERE : Reach.EVERYWHERE);
        }
    }

    enum Reach { EVERYWHERE, SOMEWHERE, TEST_OFFICE, OWN, ASSIGNED }

    static final String UNKNOWN_PARCEL = "/api/v1/parcels/DEMO-PCL-DEMOIT-2026-999999-0";

    @FunctionalInterface
    interface Request {
        AbstractMockHttpServletRequestBuilder<?> build();
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
                    .contentType(JSON).content("{\"reason\":\"permission matrix test\"}")),
            new Endpoint("GET /admin/mfa-reset-requests", Permission.ADMIN_MFA_RESET, () -> get("/api/v1/admin/mfa-reset-requests")),
            new Endpoint("GET /identity/reset-confirmations", Permission.IDENTITY_RESET_CONFIRM, () -> get("/api/v1/identity/reset-confirmations"), true),
            new Endpoint("POST /identity/reset-confirmations/{id}/confirm", Permission.IDENTITY_RESET_CONFIRM, () -> post("/api/v1/identity/reset-confirmations/" + SOMEONE + "/confirm")
                    .contentType(JSON).content("{\"identityCheck\":\"matrix\"}"), true),
            new Endpoint("POST /identity/reset-confirmations/{id}/reject", Permission.IDENTITY_RESET_CONFIRM, () -> post("/api/v1/identity/reset-confirmations/" + SOMEONE + "/reject")
                    .contentType(JSON).content(DECISION), true),
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
            new Endpoint("GET /parcels?office=DEMOIT", Permission.PARCEL_READ, () -> get("/api/v1/parcels").param("office", "DEMOIT"),
                    Reach.TEST_OFFICE),
            new Endpoint("POST /parcels", Permission.PARCEL_CREATE, () -> post("/api/v1/parcels").contentType(JSON)
                    .content("{\"officeCode\":\"DEMOIT\",\"locality\":\"Matrix\"}"), Reach.TEST_OFFICE),
            new Endpoint("GET /parcels/{unknown}", Permission.PARCEL_READ, () -> get(UNKNOWN_PARCEL)),
            new Endpoint("GET /parcels/{unknown}/versions/1", Permission.PARCEL_READ, () -> get(UNKNOWN_PARCEL + "/versions/1")),
            new Endpoint("GET /parcels/{unknown}/alerts", Permission.ALERT_REVIEW, () -> get(UNKNOWN_PARCEL + "/alerts")),
            new Endpoint("GET /alerts?office=DEMOIT", Permission.ALERT_REVIEW, () -> get("/api/v1/alerts").param("office", "DEMOIT"),
                    Reach.TEST_OFFICE),
            new Endpoint("GET /alerts/{unknown}", Permission.ALERT_REVIEW, () -> get("/api/v1/alerts/" + SOMEONE)),
            new Endpoint("POST /alerts/{unknown}/resolution", Permission.ALERT_RESOLVE, () -> post("/api/v1/alerts/" + SOMEONE + "/resolution")
                    .contentType(JSON).content("{\"expectedVersion\":0,\"resolution\":\"NO_CONFLICT\",\"note\":\"permission matrix\"}")),
            new Endpoint("POST /parcels/{unknown}/versions", Permission.PARCEL_CORRECT, () -> post(UNKNOWN_PARCEL + "/versions")
                    .contentType(JSON).content("{\"expectedVersion\":1,\"reason\":\"permission matrix\"}")),
            new Endpoint("GET /application-types", Permission.APPLICATION_CREATE, () -> get("/api/v1/application-types"), Reach.OWN),
            new Endpoint("GET /applications", Permission.APPLICATION_READ, () -> get("/api/v1/applications"), Reach.OWN),
            new Endpoint("POST /applications", Permission.APPLICATION_CREATE, () -> post("/api/v1/applications").contentType(JSON)
                    .content("{\"typeCode\":\"DEMO_NOTHING\",\"data\":{}}"), Reach.OWN),
            new Endpoint("GET /applications/{unknown}", Permission.APPLICATION_READ, () -> get("/api/v1/applications/" + SOMEONE), Reach.OWN),
            new Endpoint("PUT /applications/{unknown}", Permission.APPLICATION_CREATE, () -> put("/api/v1/applications/" + SOMEONE)
                    .contentType(JSON).content("{\"expectedVersion\":0,\"data\":{}}"), Reach.OWN),
            new Endpoint("DELETE /applications/{unknown}", Permission.APPLICATION_CREATE, () -> delete("/api/v1/applications/" + SOMEONE)
                    .param("expectedVersion", "0"), Reach.OWN),
            new Endpoint("POST /applications/{unknown}/submission", Permission.APPLICATION_SUBMIT, () -> post("/api/v1/applications/"
                    + SOMEONE + "/submission").contentType(JSON).content("{\"expectedVersion\":0}"), Reach.OWN),
            new Endpoint("GET /applications/{unknown}/documents", Permission.APPLICATION_READ, () -> get("/api/v1/applications/" + SOMEONE
                    + "/documents"), Reach.OWN),
            new Endpoint("POST /applications/{unknown}/documents/{category}", Permission.APPLICATION_CREATE, () -> multipart(
                    "/api/v1/applications/" + SOMEONE + "/documents/DEMO_IDENTITY").file(new MockMultipartFile("file", "a.pdf",
                    "application/pdf", new byte[] {'%', 'P', 'D', 'F'})), Reach.OWN),
            new Endpoint("GET /applications/{unknown}/documents/{v}/content", Permission.APPLICATION_READ, () -> get("/api/v1/applications/"
                    + SOMEONE + "/documents/" + SOMEONE + "/content"), Reach.OWN),
            new Endpoint("POST /applications/{unknown}/response", Permission.APPLICATION_RESPOND, () -> post("/api/v1/applications/"
                    + SOMEONE + "/response").contentType(JSON).content("{\"expectedVersion\":0}"), Reach.OWN),
            new Endpoint("GET /cases?office=DEMOIT", Permission.APPLICATION_READ, () -> get("/api/v1/cases").param("office", "DEMOIT"),
                    Reach.TEST_OFFICE),
            new Endpoint("GET /cases/{unknown}", Permission.APPLICATION_READ, () -> get("/api/v1/cases/" + SOMEONE)),
            new Endpoint("GET /cases/{unknown}/documents", Permission.APPLICATION_READ, () -> get("/api/v1/cases/" + SOMEONE + "/documents")),
            new Endpoint("GET /cases/{unknown}/documents/{v}/content", Permission.APPLICATION_READ, () -> get("/api/v1/cases/" + SOMEONE
                    + "/documents/" + SOMEONE + "/content")),
            new Endpoint("POST /cases/{unknown}/acceptance", Permission.COMPLETENESS_REVIEW, () -> post("/api/v1/cases/" + SOMEONE
                    + "/acceptance").contentType(JSON).content("{\"expectedVersion\":0}")),
            new Endpoint("POST /cases/{unknown}/information-request", Permission.COMPLETENESS_REQUEST_INFO, () -> post("/api/v1/cases/"
                    + SOMEONE + "/information-request").contentType(JSON)
                    .content("{\"expectedVersion\":0,\"categories\":[\"DEMO_IDENTITY\"],\"message\":\"permission matrix\"}")),
            new Endpoint("GET /reviews?stage=REVIEW", Permission.APPLICATION_READ, () -> get("/api/v1/reviews").param("stage", "REVIEW"),
                    Reach.ASSIGNED),
            new Endpoint("GET /reviews/{unknown}", Permission.APPLICATION_READ, () -> get("/api/v1/reviews/" + SOMEONE), Reach.ASSIGNED),
            new Endpoint("GET /assigned-cases/{unknown}", Permission.APPLICATION_READ, () -> get("/api/v1/assigned-cases/" + SOMEONE),
                    Reach.ASSIGNED),
            new Endpoint("GET /assigned-cases/{unknown}/documents", Permission.APPLICATION_READ, () -> get("/api/v1/assigned-cases/"
                    + SOMEONE + "/documents"), Reach.ASSIGNED),
            new Endpoint("GET /cases/{unknown}/review", Permission.APPLICATION_READ, () -> get("/api/v1/cases/" + SOMEONE + "/review")),
            new Endpoint("POST /reviews/{unknown}/review-claim", Permission.REVIEW_RECOMMEND, () -> post("/api/v1/reviews/" + SOMEONE
                    + "/review-claim"), Reach.ASSIGNED),
            new Endpoint("POST /reviews/{unknown}/recommendation", Permission.REVIEW_RECOMMEND, () -> post("/api/v1/reviews/" + SOMEONE
                    + "/recommendation").contentType(JSON).content("{\"expectedVersion\":0}"), Reach.ASSIGNED),
            new Endpoint("POST /reviews/{unknown}/decision-claim", Permission.DECISION_RECORD, () -> post("/api/v1/reviews/" + SOMEONE
                    + "/decision-claim"), Reach.ASSIGNED),
            new Endpoint("POST /reviews/{unknown}/return", Permission.DECISION_RECORD, () -> post("/api/v1/reviews/" + SOMEONE
                    + "/return").contentType(JSON).content("{\"expectedVersion\":0}"), Reach.ASSIGNED),
            new Endpoint("POST /reviews/{unknown}/decision", Permission.DECISION_RECORD, () -> post("/api/v1/reviews/" + SOMEONE
                    + "/decision").contentType(JSON).content("{\"expectedVersion\":0}"), Reach.ASSIGNED),
            new Endpoint("GET /reports/workload?office=DEMOIT", Permission.REPORT_WORKLOAD, () -> get("/api/v1/reports/workload")
                    .param("office", "DEMOIT"), Reach.TEST_OFFICE),
            new Endpoint("GET /reports/workload/cases?office=DEMOIT", Permission.REPORT_WORKLOAD, () -> get("/api/v1/reports/workload/cases")
                    .param("office", "DEMOIT"), Reach.TEST_OFFICE),
            new Endpoint("GET /applications/{unknown}/payment", Permission.PAYMENT_PAY, () -> get("/api/v1/applications/"
                    + SOMEONE + "/payment"), Reach.OWN),
            new Endpoint("POST /applications/{unknown}/payments", Permission.PAYMENT_PAY, () -> post("/api/v1/applications/"
                    + SOMEONE + "/payments").contentType(JSON).content("{\"scenario\":\"SUCCEED\"}"), Reach.OWN),
            new Endpoint("GET /payments", Permission.PAYMENT_RECONCILE, () -> get("/api/v1/payments")),
            new Endpoint("GET /payments/summary", Permission.PAYMENT_RECONCILE, () -> get("/api/v1/payments/summary")),
            new Endpoint("GET /payments/exceptions", Permission.PAYMENT_RECONCILE, () -> get("/api/v1/payments/exceptions")),
            new Endpoint("POST /payments/exceptions/{unknown}/resolution", Permission.PAYMENT_RECONCILE, () -> post(
                    "/api/v1/payments/exceptions/" + SOMEONE + "/resolution").contentType(JSON)
                    .content("{\"resolution\":\"NO_ACTION\",\"note\":\"Matrix check only\"}")),
            new Endpoint("GET /map/backgrounds", Permission.PARCEL_READ, () -> get("/api/v1/map/backgrounds"), true),
            new Endpoint("GET /map/geographic/tiles.pmtiles", Permission.PARCEL_READ, () -> get("/api/v1/map/geographic/tiles.pmtiles"), true),
            // Office-scoped since ADR-038: the database then filters each event by its subjects' offices.
            new Endpoint("GET /audit/events", Permission.AUDIT_EVENT_SEARCH, () -> get("/api/v1/audit/events"), Reach.SOMEWHERE),
            new Endpoint("GET /audit/timeline", Permission.AUDIT_EVENT_SEARCH, () -> get("/api/v1/audit/timeline")
                    .param("type", "APPLICATION").param("id", SOMEONE.toString()), Reach.SOMEWHERE),
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
                Permission.Scope scope = role.permissions().get(endpoint.permission());
                boolean expected = switch (endpoint.reach()) {
                    case EVERYWHERE -> scope == Permission.Scope.ANY;
                    case SOMEWHERE -> scope == Permission.Scope.ANY || scope == Permission.Scope.OFFICE;
                    case TEST_OFFICE -> scope == Permission.Scope.ANY || scope == Permission.Scope.OFFICE;
                    case OWN -> scope == Permission.Scope.OWN;
                    case ASSIGNED -> scope == Permission.Scope.ASSIGNED;
                };
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
    void applicantsReachOnlyTheirOwnRecords() throws Exception {
        String email = email();
        Browser applicant = new Browser();
        applicant.register(email, "Matrix Applicant", PASSWORD);
        applicant.login(email, PASSWORD);
        for (Endpoint endpoint : ENDPOINTS) {
            applicant.csrf();
            int status = applicant.send(endpoint.request().build()).getStatus();
            if (endpoint.reach() == Reach.OWN && BaselineRole.APPLICANT.permissions().containsKey(endpoint.permission())) {
                assertThat(status).as(endpoint.name()).isNotEqualTo(403);
            } else {
                assertThat(status).as(endpoint.name()).isEqualTo(403);
            }
        }
    }
}
