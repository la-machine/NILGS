// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * @param grants          active roles and the offices they were granted at
 * @param permissions     permission authorities from those roles ({@code audit.event.search},
 *                        {@code parcel.create@DEMO1}); in force only at level FULL
 * @param specialGrants   special grants in force (ADR-025); each use is counted
 * @param level           how far this session has been authenticated
 * @param authenticatedAt when the password was verified for this session
 */
public record AuthenticatedUser(UUID userId, String displayName, AccountType accountType, Set<RoleGrant> grants,
                                Set<String> permissions, List<ActiveGrant> specialGrants, AuthLevel level,
                                Instant authenticatedAt) implements AuditedPrincipal {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(accountType, "accountType");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        grants = Set.copyOf(grants);
        permissions = Set.copyOf(permissions);
        specialGrants = List.copyOf(specialGrants);
    }

    /** The same principal at another authentication level. */
    public AuthenticatedUser at(AuthLevel newLevel) {
        return new AuthenticatedUser(userId, displayName, accountType, grants, permissions, specialGrants, newLevel,
                authenticatedAt);
    }

    /** Role names held, whatever the office. */
    public Set<String> roles() {
        return grants.stream().map(RoleGrant::role).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<String> auditRoles() {
        return roles();
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
