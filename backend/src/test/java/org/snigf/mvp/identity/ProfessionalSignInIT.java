// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * ADR-041 step 1: a professional signs in with a password and two security keys, exactly as staff,
 * and gains nothing from it. Applicants still sign in with a password only. Officer signatures and
 * special grants stay staff-only.
 */
class ProfessionalSignInIT extends IdentityIntegrationTest {

    @Autowired
    private OfficerSignatures signatures;

    private UUID insertProfessional(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES (?, ?, ?, ?, 'PROFESSIONAL')",
                id, email, "Demo Notary", hasher.hash(PASSWORD));
        return id;
    }

    @Test
    void aProfessionalEnrolsTwoKeysThenProvesOneAtEveryLogin() throws Exception {
        String email = email();
        UUID id = insertProfessional(email);
        Browser browser = new Browser();
        assertThat(browser.login(email, PASSWORD).getContentAsString()).contains("MFA_ENROLMENT");
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"PASSWORD\"").contains("\"accountType\":\"PROFESSIONAL\"");

        Authenticator first = new Authenticator();
        register(browser, first, "Office key");
        assertThat(register(browser, new Authenticator(), "Backup key").path("enrolmentComplete").asBoolean()).isTrue();
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"FULL\"");

        logout(browser);
        assertThat(browser.login(email, PASSWORD).getContentAsString()).contains("MFA_CHALLENGE");
        assertThat(prove(browser, first).getStatus()).isEqualTo(200);
        assertThat(actions(id)).containsSubsequence("MFA_ENROLLED", "MFA_ENROLLED", "LOGOUT", "LOGIN_SUCCEEDED", "MFA_VERIFIED");
    }

    @Test
    void aProfessionalWithEveryFactorProvenHoldsNoPermission() throws Exception {
        String email = email();
        UUID id = insertProfessional(email);
        Browser browser = new Browser();
        browser.login(email, PASSWORD);
        register(browser, new Authenticator(), "Office key");
        register(browser, new Authenticator(), "Backup key");

        assertThat(browser.session().getContentAsString()).contains("\"permissions\":[]");
        for (String path : java.util.List.of("/api/v1/parcels?office=DEMOIT", "/api/v1/register/titles?office=DEMOIT", "/api/v1/applications",
                "/api/v1/admin/users", "/api/v1/audit/events")) {
            MockHttpServletResponse response = browser.send(get(path));
            assertThat(response.getStatus()).as(path).isEqualTo(403);
        }
        assertThat(browser.send(get("/api/v1/auth/mfa/keys")).getStatus()).as("but they manage their own keys").isEqualTo(200);

        assertThatThrownBy(() -> signatures.begin(id, new byte[] {1}))
                .as("a professional signs nothing in the register").isInstanceOf(OfficerSignatures.Refused.class);
    }

    @Test
    void applicantsStillSignInWithAPasswordOnly() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Applicant Only", PASSWORD);
        assertThat(browser.login(email, PASSWORD).getContentAsString()).contains("\"nextStep\":\"DONE\"");
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"FULL\"").contains("\"accountType\":\"APPLICANT\"");
    }
}
