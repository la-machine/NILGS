// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR-041 step 3: an approved notary's session holds the four deposit permissions, submit only at their
 * approved offices; a sworn surveyor's holds none; suspension takes them away at the very next request and
 * reinstatement gives them back; an office change moves them. Staff never obtain them: an assignment
 * request for NOTARY, or a custom role carrying one, is refused.
 */
class ProfessionalPermissionsIT extends IdentityIntegrationTest {

    private static final List<String> NOTARY_AT_DEMOIT = List.of("register.deposit.document#own", "register.deposit.read#own",
            "register.deposit.submit@DEMOIT", "register.deposit.withdraw#own");

    private String body(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(map);
    }

    private List<String> permissions(Browser browser) throws Exception {
        JsonNode session = json.readTree(browser.session().getContentAsString());
        return session.path("permissions").valueStream().map(n -> n.asString("")).sorted().toList();
    }

    private String decided(SignedIn approver, MockHttpServletResponse requested) throws Exception {
        assertThat(requested.getStatus()).as(requested.getContentAsString()).isEqualTo(201);
        String request = json.readTree(requested.getContentAsString()).path("id").asString("");
        MockHttpServletResponse decision = sendJson(approver.browser(), post("/api/v1/admin/professional-requests/" + request + "/approval"), "{}");
        assertThat(decision.getStatus()).as(decision.getContentAsString()).isEqualTo(200);
        return request;
    }

    @Test
    void anApprovedNotaryHoldsTheDepositPermissionsAtTheirOfficesOnly() throws Exception {
        SignedIn notary = activeProfessional("NOTARY", "DEMOIT");
        assertThat(permissions(notary.browser())).containsExactlyElementsOf(NOTARY_AT_DEMOIT);
        assertThat(notary.browser().session().getContentAsString()).contains("\"role\":\"NOTARY\"").contains("\"specialGrants\":[]");
    }

    @Test
    void aSwornSurveyorHoldsNoOperationalPermission() throws Exception {
        SignedIn surveyor = activeProfessional("SWORN_SURVEYOR", "DEMOIT");
        assertThat(permissions(surveyor.browser())).isEmpty();
    }

    @Test
    void suspensionTakesThePermissionsAwayAtTheNextRequestAndReinstatementGivesThemBack() throws Exception {
        SignedIn notary = activeProfessional("NOTARY", "DEMOIT");
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");

        MockHttpServletResponse suspended = sendJson(first.browser(), post("/api/v1/admin/professionals/" + notary.id() + "/suspension"),
                body("reason", "Deposits reported as abusive"));
        assertThat(suspended.getStatus()).isEqualTo(204);
        assertThat(permissions(notary.browser())).as("the same session, its next request").isEmpty();

        decided(second, sendJson(first.browser(), post("/api/v1/admin/professionals/" + notary.id() + "/requests"),
                body("kind", "REINSTATEMENT", "officesAdd", List.of(), "officesRemove", List.of(), "reason", "The report was unfounded")));
        assertThat(permissions(notary.browser())).containsExactlyElementsOf(NOTARY_AT_DEMOIT);
    }

    @Test
    void anOfficeChangeMovesTheDepositPermission() throws Exception {
        SignedIn notary = activeProfessional("NOTARY", "DEMOIT");
        insertStaff(email(), "INTAKE_OFFICER", "DEMOIT2");   // makes sure the office exists
        SignedIn first = signedIn("SYSTEM_ADMIN");
        SignedIn second = signedIn("SYSTEM_ADMIN");
        decided(second, sendJson(first.browser(), post("/api/v1/admin/professionals/" + notary.id() + "/requests"),
                body("kind", "OFFICE_CHANGE", "officesAdd", List.of("DEMOIT2"), "officesRemove", List.of("DEMOIT"), "reason", "Moved to DEMOIT2")));
        assertThat(permissions(notary.browser())).contains("register.deposit.submit@DEMOIT2").doesNotContain("register.deposit.submit@DEMOIT");
    }

    @Test
    void staffNeverObtainAProfessionalRole() throws Exception {
        SignedIn admin = signedIn("SYSTEM_ADMIN");
        UUID clerk = insertStaff(email(), "INTAKE_OFFICER");
        MockHttpServletResponse assignment = sendJson(admin.browser(), post("/api/v1/admin/users/" + clerk + "/roles"),
                body("role", "NOTARY", "officeCode", "DEMOIT", "reason", "Trying to make a clerk a notary"));
        assertThat(assignment.getStatus()).as(assignment.getContentAsString()).isIn(409, 422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_assignment_request WHERE user_id = ? AND role = 'NOTARY'", Long.class, clerk)).isZero();

        MockHttpServletResponse custom = sendJson(admin.browser(), post("/api/v1/admin/role-requests"), body("kind", "CREATE", "roleCode", "DEPOSIT_CLERK",
                "nameFr", "Commis", "nameEn", "Clerk", "permissions", Map.of("register.deposit.submit", "OFFICE"), "reason", "A custom role with deposits"));
        assertThat(custom.getStatus()).as(custom.getContentAsString()).isIn(409, 422);
    }
}
