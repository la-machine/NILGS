// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

/**
 * Keyed hash of recovery codes (ADR-023). Codes carry 80 random bits, so a keyed MAC
 * whose key never leaves OpenBao resists offline guessing without Argon2's cost, and lets
 * a code be checked in one step instead of one Argon2 per stored code.
 */
public interface RecoveryCodeHasher {

    /** Hash of a normalised code ({@link RecoveryCode#normalise}). */
    String hash(String normalisedCode);

    /** Constant-time comparison with a stored hash. */
    boolean matches(String normalisedCode, String storedHash);
}
