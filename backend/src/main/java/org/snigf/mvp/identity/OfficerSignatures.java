// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.UUID;

/**
 * Officers sign records with their own security key (ADR-019), in a ceremony separate from
 * sign-in: the challenge is SHA-256 of the record's canonical form and a single-use server
 * nonce, user verification is required, only the officer's own credentials are allowed, and
 * a started ceremony is finished once, by the same officer, within its timeout.
 */
public interface OfficerSignatures {

    /** A started ceremony: its single-use id and the options for {@code navigator.credentials.get}. */
    record Started(String ceremonyId, String optionsJson) {
    }

    /** The signature does not verify, or the ceremony is unknown, expired, used or someone else's. */
    class Refused extends RuntimeException {
        public Refused(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** Starts a ceremony over {@code canonical} for an enabled staff account holding a key. */
    Started begin(UUID officerId, byte[] canonical);

    /**
     * Verifies the authenticator's response against the ceremony and against {@code canonical}
     * as it stands now: bytes other than those the ceremony began with are refused.
     */
    OfficerSignature finish(String ceremonyId, UUID officerId, byte[] canonical, String responseJson);

    /** Verifies a stored signature from its own contents, independently of any ceremony or stored key. */
    boolean verify(byte[] canonical, OfficerSignature signature);
}
