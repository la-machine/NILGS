// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.util.UUID;

/**
 * Port: the WebAuthn protocol (W3C Web Authentication, relying-party side). A ceremony
 * starts with options sent to the browser and ends with the authenticator's signed
 * response; each started ceremony can be finished once, by the same account, within its
 * timeout. Options and responses travel as WebAuthn JSON.
 */
public interface WebAuthnCeremonies {

    /** A started ceremony: its single-use id and the options for {@code navigator.credentials}. */
    record Started(String ceremonyId, String optionsJson) {
    }

    /** A registered authenticator. */
    record Registered(String credentialId, String label) {
    }

    /** The response does not verify, or the ceremony is unknown, expired, or someone else's. */
    class Rejected extends RuntimeException {
        public Rejected(String reason) {
            super(reason);
        }

        public Rejected(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** Options to create a credential, excluding the account's existing authenticators. */
    Started beginRegistration(UUID userId, String displayName);

    /**
     * Verifies the attestation (challenge, origin, relying party, user verification) and
     * stores the public key.
     */
    Registered finishRegistration(String ceremonyId, UUID userId, String responseJson, String label);

    /** Options to prove possession of one of the account's authenticators. */
    Started beginAssertion(UUID userId);

    /**
     * Verifies the assertion (challenge, origin, signature, user verification, signature
     * counter) and that the credential belongs to {@code userId}.
     */
    void finishAssertion(String ceremonyId, UUID userId, String responseJson);

    int countCredentials(UUID userId);
}
