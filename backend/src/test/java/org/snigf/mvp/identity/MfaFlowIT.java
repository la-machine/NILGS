// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security step 4b end to end: staff enrol two WebAuthn authenticators, then prove one at
 * every login, or use a one-time recovery code; every failure counts toward the lockout.
 * The authenticators are webauthn4j emulators producing genuine attestations and
 * signatures, so the server verifies real WebAuthn data (ADR-016, ADR-023).
 */
class MfaFlowIT extends IdentityIntegrationTest {

    // --- Tests ----------------------------------------------------------------------------

    @Test
    void staffEnrolTwoAuthenticatorsThenProveOneAtEveryLogin() throws Exception {
        String email = email();
        UUID staff = insertStaff(email, "CADASTRAL_OFFICER");
        Browser browser = new Browser();
        assertThat(browser.login(email, PASSWORD).getContentAsString()).contains("MFA_ENROLMENT");

        Authenticator first = new Authenticator();
        JsonNode one = register(browser, first, "Office key");
        assertThat(one.path("registered").asInt()).isEqualTo(1);
        assertThat(one.path("nextStep").asString("")).isEqualTo("MFA_ENROLMENT");
        assertThat(browser.send(get("/api/v1/parcels")).getStatus()).as("still a password session").isEqualTo(403);

        String sessionBefore = browser.cookies.get(SESSION_COOKIE);
        JsonNode two = register(browser, new Authenticator(), "Backup key");
        assertThat(two.path("enrolmentComplete").asBoolean()).isTrue();
        assertThat(two.path("nextStep").asString("")).isEqualTo("DONE");
        assertThat(two.path("recoveryCodes")).hasSize(10);
        assertThat(two.path("recoveryCodes").get(0).asString("")).matches("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}");
        assertThat(browser.cookies.get(SESSION_COOKIE)).as("new session id when elevated").isNotEqualTo(sessionBefore);
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"FULL\"");
        assertThat(browser.send(get("/api/v1/parcels")).getStatus()).as("full session passes security").isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT mfa_enrolled_at IS NOT NULL FROM app_user WHERE id = ?", Boolean.class, staff))
                .isTrue();

        logout(browser);
        assertThat(browser.login(email, PASSWORD).getContentAsString()).contains("MFA_CHALLENGE");
        MockHttpServletResponse proof = prove(browser, first);
        assertThat(proof.getStatus()).isEqualTo(200);
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"FULL\"").contains("CADASTRAL_OFFICER");
        assertThat(actions(staff)).containsSubsequence("MFA_ENROLLED", "MFA_ENROLLED", "LOGOUT", "LOGIN_SUCCEEDED",
                "MFA_VERIFIED");
    }

    @Test
    void responsesFromAnotherOriginOrReplayedAreRefusedAndCountTowardLockout() throws Exception {
        Enrolled staff = enrol();
        Browser browser = new Browser();
        browser.login(staff.email(), PASSWORD);

        Authenticator phishing = new Authenticator("https://evil.example");
        phishing.platform.setAuthenticatorAdaptor(staff.first().platform.getAuthenticatorAdaptor());   // the real key, on a fake site
        String options = options(browser, "assertion");
        MockHttpServletResponse phished = browser.send(json(post(MFA + "/webauthn/assertion"), phishing.get(options)));
        assertThat(phished.getStatus()).isEqualTo(401);
        assertThat(phished.getContentAsString()).contains("SECOND_FACTOR_INVALID");

        String fresh = options(browser, "assertion");
        String response = staff.first().get(fresh);
        assertThat(browser.send(json(post(MFA + "/webauthn/assertion"), response)).getStatus()).isEqualTo(200);
        logout(browser);
        browser.login(staff.email(), PASSWORD);
        browser.csrf();
        assertThat(browser.send(json(post(MFA + "/webauthn/assertion"), response)).getStatus())
                .as("a replayed response finds no ceremony").isEqualTo(401);

        assertThat(jdbc.queryForList("SELECT details->>'reason' FROM audit_event WHERE entity_id = ? AND action = 'LOGIN_FAILED'"
                + " ORDER BY chain_pos", String.class, staff.id())).containsExactly("SECOND_FACTOR_INVALID", "SECOND_FACTOR_INVALID");
    }

    @Test
    void anotherAccountsAuthenticatorIsRefusedEvenWhenItSignsTheRightChallenge() throws Exception {
        Enrolled victim = enrol();
        Enrolled intruder = enrol();
        Browser browser = new Browser();
        browser.login(victim.email(), PASSWORD);

        String options = options(browser, "assertion");
        String signedByIntruder = intruder.first().get(options, intruder.first().credentialId);
        MockHttpServletResponse refused = browser.send(json(post(MFA + "/webauthn/assertion"), signedByIntruder));

        assertThat(refused.getStatus()).isEqualTo(401);
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"PASSWORD\"");
    }

    @Test
    void aRecoveryCodeWorksOnceAndAsksForAReplacementAuthenticator() throws Exception {
        Enrolled staff = enrol();
        String code = staff.recoveryCodes().get(3);
        Browser browser = new Browser();
        browser.login(staff.email(), PASSWORD);

        MockHttpServletResponse used = recover(browser, code.toLowerCase().replace("-", " "));   // forgiving input
        assertThat(used.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(used.getContentAsString());
        assertThat(body.path("remainingRecoveryCodes").asInt()).isEqualTo(9);
        assertThat(body.path("replaceAuthenticator").asBoolean()).isTrue();
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"FULL\"");

        logout(browser);
        browser.login(staff.email(), PASSWORD);
        assertThat(recover(browser, code).getStatus()).as("single use").isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_recovery_code WHERE user_id = ? AND used_at IS NOT NULL",
                Long.class, staff.id())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT code_hash FROM mfa_recovery_code WHERE user_id = ?", String.class, staff.id()))
                .allSatisfy(hash -> assertThat(hash).matches("v1:[0-9a-f]{64}").doesNotContain(code.replace("-", "")));
        assertThat(actions(staff.id())).contains("MFA_RECOVERY_CODE_USED");
    }

    @Test
    void fiveWrongSecondFactorsLockTheAccountAndEndTheSession() throws Exception {
        Enrolled staff = enrol();
        Browser browser = new Browser();
        browser.login(staff.email(), PASSWORD);

        for (int i = 0; i < 5; i++) {
            assertThat(recover(browser, "0000-0000-0000-000" + i).getStatus()).isEqualTo(401);
        }

        assertThat(browser.session().getStatus()).as("session ended at the lock").isEqualTo(401);
        assertThat(browser.login(staff.email(), PASSWORD).getStatus()).as("locked").isEqualTo(401);
        assertThat(actions(staff.id())).contains("ACCOUNT_LOCKED");
    }

    @Test
    void aPasswordSessionCannotAddAnAuthenticatorOnceEnrolled() throws Exception {
        Enrolled staff = enrol();
        Browser browser = new Browser();
        browser.login(staff.email(), PASSWORD);
        browser.csrf();

        MockHttpServletResponse refused = browser.send(post(MFA + "/webauthn/registration/options"));
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("MFA_NOT_ALLOWED");

        prove(browser, staff.second());
        browser.csrf();
        assertThat(browser.send(post(MFA + "/webauthn/registration/options")).getStatus())
                .as("allowed after proving the second factor").isEqualTo(200);
    }

    @Test
    void everyCeremonyRequiresUserVerificationAndAllowsOnlyTheAccountsKeys() throws Exception {
        Enrolled staff = enrol();
        Browser browser = new Browser();
        browser.login(staff.email(), PASSWORD);

        JsonNode request = json.readTree(options(browser, "assertion"));
        assertThat(request.path("userVerification").asString("")).isEqualTo("required");
        assertThat(request.path("rpId").asString("")).isEqualTo("localhost");
        assertThat(request.path("allowCredentials")).extracting(c -> c.path("id").asString(""))
                .containsExactlyInAnyOrder(staff.first().credentialId, staff.second().credentialId);

        Browser newcomer = new Browser();
        newcomer.login(staff.email().replace("user-", "nobody-"), PASSWORD);   // no such account: no options either
        String email = email();
        insertStaff(email, "REVIEWER");
        newcomer.login(email, PASSWORD);
        JsonNode creation = json.readTree(options(newcomer, "registration"));
        assertThat(creation.path("authenticatorSelection").path("userVerification").asString("")).isEqualTo("required");
        assertThat(creation.path("authenticatorSelection").path("residentKey").asString("")).isEqualTo("discouraged");
        assertThat(creation.path("attestation").asString("")).isEqualTo("none");
        assertThat(creation.path("user").path("name").asString("")).as("account id, not e-mail").doesNotContain("@");
    }

    @Test
    void applicantsHaveNoSecondFactor() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Applicant Only", PASSWORD);
        browser.login(email, PASSWORD);
        browser.csrf();

        MockHttpServletResponse refused = browser.send(post(MFA + "/webauthn/registration/options"));
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("MFA_NOT_ALLOWED");
    }

    @Test
    void aCeremonyBelongsToTheSessionThatStartedIt() throws Exception {
        Enrolled staff = enrol();
        Browser started = new Browser();
        started.login(staff.email(), PASSWORD);
        String response = staff.first().get(options(started, "assertion"));

        Browser other = new Browser();
        other.login(staff.email(), PASSWORD);
        other.csrf();
        assertThat(other.send(json(post(MFA + "/webauthn/assertion"), response)).getStatus()).isEqualTo(401);
    }

    @Test
    void storedCredentialsHoldPublicKeysOnly() throws Exception {
        Enrolled staff = enrol();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.label, c.public_key, e.display_name FROM user_credentials c
                  JOIN user_entities e ON e.id = c.user_entity_user_id WHERE e.app_user_id = ? ORDER BY c.label""", staff.id());
        assertThat(rows).extracting(r -> r.get("label")).containsExactly("Backup key", "Office key");
        assertThat(rows).allSatisfy(r -> assertThat((byte[]) r.get("public_key")).isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT name FROM user_entities WHERE app_user_id = ?", String.class, staff.id()))
                .as("WebAuthn user name is the account id, not the e-mail").isEqualTo(staff.id().toString());
    }
}
