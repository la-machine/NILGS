// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;

/**
 * A security key registered for an account, as its owner sees it (ADR-028): never the
 * public key or any attestation data.
 *
 * @param id       the credential id (base64url), used to remove the key
 * @param label    the name the owner gave it
 * @param lastUsed null if never used to sign in
 */
public record RegisteredKey(String id, String label, Instant created, Instant lastUsed) {
}
