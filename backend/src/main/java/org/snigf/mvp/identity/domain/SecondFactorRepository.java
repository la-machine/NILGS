// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Second-factor state of an account: enrolment and one-time recovery codes. */
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

    /**
     * Administrator reset (ADR-016): removes every authenticator of the account, invalidates
     * its unused recovery codes and clears the enrolment, so the next login enrols again.
     * Returns the number of authenticators removed.
     */
    int resetSecondFactor(UUID userId, Instant at);
}
