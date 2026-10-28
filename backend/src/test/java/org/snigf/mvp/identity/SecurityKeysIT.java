// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * ADR-028: a staff member lists their own security keys and removes a lost one, so that a
 * key found by someone else stops working; the last key stays; nobody touches another
 * account's keys; removals are audited.
 */
class SecurityKeysIT extends IdentityIntegrationTest {

    static final String KEYS = MFA + "/keys";

    @Test
    void aRemovedKeyNoLongerSignsInAndTheLastKeyStays() throws Exception {
        Enrolled person = enrol();
        Browser browser = new Browser();
        browser.login(person.email(), PASSWORD);
        assertThat(prove(browser, person.second()).getStatus()).isEqualTo(200);
        browser.csrf();

        JsonNode listed = json.readTree(browser.send(get(KEYS)).getContentAsString());
        assertThat(listed.path("required").asInt()).isEqualTo(2);
        assertThat(listed.path("keys").findValuesAsString("label")).containsExactly("Office key", "Backup key");
        assertThat(listed.toString()).doesNotContain("publicKey").doesNotContain("attestation");
        String lost = id(listed, "Office key");
        String kept = id(listed, "Backup key");

        MockHttpServletResponse removed = browser.send(delete(KEYS + "/" + lost));
        assertThat(removed.getStatus()).as(removed.getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(removed.getContentAsString()).path("remaining").asInt()).isEqualTo(1);
        assertRefused(browser.send(delete(KEYS + "/" + kept)), 409, "LAST_KEY");
        assertRefused(browser.send(delete(KEYS + "/" + lost)), 404, "KEY_NOT_FOUND");

        // The lost key is no longer offered at sign-in, so it cannot even answer the challenge.
        Browser thief = new Browser();
        thief.login(person.email(), PASSWORD);
        assertThat(options(thief, "assertion")).doesNotContain(lost).contains(kept);
        assertThatThrownBy(() -> prove(thief, person.first())).as("the lost key no longer works")
                .isInstanceOf(com.webauthn4j.test.authenticator.webauthn.exception.NotAllowedException.class);
        Browser owner = new Browser();
        owner.login(person.email(), PASSWORD);
        assertThat(prove(owner, person.second()).getStatus()).isEqualTo(200);

        JsonNode details = json.readTree(jdbc.queryForObject("SELECT details::text FROM audit_event"
                + " WHERE action = 'MFA_KEY_REMOVED' AND entity_id = ?", String.class, person.id()));
        assertThat(details.path("label").asString("")).isEqualTo("Office key");
        assertThat(details.path("remaining").asInt()).isEqualTo(1);
    }

    @Test
    void keysAreManagedOnlyByTheirOwnerWithEveryFactorProven() throws Exception {
        Enrolled person = enrol();
        Browser full = new Browser();
        full.login(person.email(), PASSWORD);
        prove(full, person.first());
        full.csrf();
        String key = id(json.readTree(full.send(get(KEYS)).getContentAsString()), "Office key");

        Browser halfway = new Browser();
        halfway.login(person.email(), PASSWORD);
        halfway.csrf();
        assertRefused(halfway.send(get(KEYS)), 403, "MFA_NOT_ALLOWED");
        assertRefused(halfway.send(delete(KEYS + "/" + key)), 403, "MFA_NOT_ALLOWED");

        SignedIn other = signedIn("REVIEWER");
        assertRefused(other.browser().send(delete(KEYS + "/" + key)), 404, "KEY_NOT_FOUND");
        assertThat(json.readTree(full.send(get(KEYS)).getContentAsString()).path("keys").size()).isEqualTo(2);
    }

    private static String id(JsonNode listed, String label) {
        for (JsonNode key : listed.path("keys")) {
            if (key.path("label").asString("").equals(label)) {
                return key.path("id").asString("");
            }
        }
        throw new AssertionError("no key labelled " + label);
    }

    private static void assertRefused(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains(code);
    }
}
