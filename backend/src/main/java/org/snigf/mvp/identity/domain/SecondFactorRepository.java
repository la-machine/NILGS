// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Second-factor state of an account: enrolment, registered keys and one-time recovery codes. */
public interface SecondFactorRepository {

    /** A stored, unused recovery code. */
    record StoredRecoveryCode(UUID id, String hash) {
    }

    /** Marks the account's second-factor enrolment as complete (the login then asks for it). */
    void completeEnrolment(UUID userId, Instant at);

    /** Invalidates every unused code of the account and stores the new hashes. */
    void replaceRecoveryCodes(UUID userId, List<String> hashes, Instant at);

    List<StoredRecoveryCode> unusedRecoveryCodes(UUID userId);

    /** Consumes a code atomically; false if it was used or invalidated meanwhile. */
    boolean consumeRecoveryCode(UUID codeId, Instant at);

    /** The account's registered security keys, oldest first. */
    List<RegisteredKey> keys(UUID userId);

    /**
     * Removes one of the account's keys; false if it is not one of them. The database refuses
     * to remove the last key of an enrolled account (V10). A full reset is not done here: only
     * a confirmed reset request performs it (MfaResetRepository, ADR-028).
     */
    boolean removeKey(UUID userId, String credentialId);
}
