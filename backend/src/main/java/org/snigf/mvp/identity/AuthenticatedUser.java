// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.audit.AuditedPrincipal;

/**
 * The principal of an authenticated request, rebuilt from the database on every request
 * (the session stores only the user id, level and login time), so a disabled account or a
 * revoked role takes effect at once. Controllers receive it with
 * {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * @param userId          the account
 * @param displayName     name shown in the interface
 * @param accountType     applicant or staff
 * @param roles           active role names (upper snake case)
 * @param level           how far this session has been authenticated
 * @param authenticatedAt when the password was verified for this session
 */
public record AuthenticatedUser(UUID userId, String displayName, AccountType accountType, Set<String> roles,
                                AuthLevel level, Instant authenticatedAt) implements AuditedPrincipal {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(accountType, "accountType");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        roles = Set.copyOf(roles);
    }

    @Override
    public Set<String> auditRoles() {
        return roles;
    }

    public boolean fullyAuthenticated() {
        return level == AuthLevel.FULL;
    }

    /** The account id only: names and roles stay out of logs. */
    @Override
    public String toString() {
        return "AuthenticatedUser[" + userId + ", " + level + "]";
    }
}
