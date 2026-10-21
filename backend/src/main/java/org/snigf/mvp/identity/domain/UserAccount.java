// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.UUID;

import org.snigf.mvp.identity.AccountType;

/**
 * An account as the login needs it. The password hash never leaves the identity module.
 *
 * @param id                identifier
 * @param email             normalised e-mail address
 * @param displayName       name shown in the interface
 * @param passwordHash      peppered Argon2id hash ({@link PasswordHasher})
 * @param accountType       applicant or staff
 * @param preferredLanguage {@code fr} or {@code en}
 * @param enabled           false once an administrator disables the account
 * @param secondFactorReady a second factor is registered (staff), so login asks for it
 * @param failedLogins      consecutive wrong passwords
 * @param lockedUntil       locked until this time, or null
 */
public record UserAccount(UUID id, EmailAddress email, String displayName, String passwordHash,
                          AccountType accountType, String preferredLanguage, boolean enabled,
                          boolean secondFactorReady, int failedLogins, Instant lockedUntil) {

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    @Override
    public String toString() {
        return "UserAccount[" + id + ", " + accountType + "]";
    }
}
