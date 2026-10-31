// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The WebAuthn challenge of a record signature (ADR-019): SHA-256(canonical form ‖ nonce). */
public final class SigningChallenge {

    private SigningChallenge() {
    }

    public static byte[] of(byte[] canonical, byte[] nonce) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonical);
            digest.update(nonce);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
