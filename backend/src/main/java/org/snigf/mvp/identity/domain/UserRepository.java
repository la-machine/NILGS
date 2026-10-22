// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.identity.RoleGrant;

/** The account store. Every change is a single atomic statement; nothing is read-modify-write. */
public interface UserRepository {

    Optional<UserAccount> findByEmail(EmailAddress email);

    Optional<UserAccount> findById(UUID id);

    /**
     * Creates an applicant account with its APPLICANT role. Returns empty when the e-mail
     * address is already registered; nothing is changed then.
     */
    Optional<UUID> createApplicant(UUID id, EmailAddress email, String displayName, String passwordHash,
                                   String preferredLanguage);

    /** Result of a wrong password: the new counter, and the lock if this failure set one. */
    record Failure(int failedLogins, Instant lockedUntil, boolean lockedNow) {
    }

    /**
     * Counts a wrong password atomically; on the {@code policy.maxFailures()}-th consecutive
     * failure, locks the account until {@code policy.lockedUntil(now)} and resets the counter.
     */
    Failure recordFailedLogin(UUID id, LockoutPolicy policy, Instant now);

    /** Clears the failure counter after a correct password. */
    void recordSuccessfulLogin(UUID id);

    /** Replaces the hash (stronger parameters or a newer pepper version). */
    void updatePasswordHash(UUID id, String passwordHash);

    /** Roles currently held (not revoked), with their offices. */
    Set<RoleGrant> activeGrants(UUID id);
}
