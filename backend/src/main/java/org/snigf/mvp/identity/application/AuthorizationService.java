// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.AuthLevel;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.RoleGrant;
import org.snigf.mvp.identity.domain.AuthorizationRepository;
import org.snigf.mvp.identity.domain.EffectivePermissions;
import org.snigf.mvp.identity.domain.RoleDefinition;
import org.snigf.mvp.identity.domain.SeparationOfDuties;
import org.snigf.mvp.identity.domain.SpecialGrant;
import org.snigf.mvp.identity.domain.UserAccount;
import org.snigf.mvp.identity.domain.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Builds what a user may do (ADR-025): active role grants, the current definitions of those
 * roles, and special grants in force. Called at login and on every request, so a changed role
 * definition, a revoked role or an expired grant counts at once.
 */
@Service
public class AuthorizationService {

    private final UserRepository users;
    private final AuthorizationRepository authorization;
    private final Clock clock;

    public AuthorizationService(UserRepository users, AuthorizationRepository authorization, Clock clock) {
        this.users = users;
        this.authorization = authorization;
        this.clock = clock;
    }

    public AuthenticatedUser principal(UserAccount account, AuthLevel level, Instant authenticatedAt) {
        Set<RoleGrant> grants = users.activeGrants(account.id());
        Set<String> permissions = EffectivePermissions.authorities(grants, roles());
        List<SpecialGrant> special = account.accountType() == AccountType.STAFF
                ? authorization.grantsInForce(account.id(), clock.instant()) : List.of();
        return new AuthenticatedUser(account.id(), account.displayName(), account.accountType(), grants, permissions,
                special.stream().map(SpecialGrant::asActive).toList(), level, authenticatedAt);
    }

    /**
     * True when the user would hold administration and audit permissions together after
     * also receiving {@code extraRoles} and {@code extraPermissions}.
     */
    public boolean wouldConflict(java.util.UUID userId, Collection<String> extraRoles, Collection<String> extraPermissions) {
        List<String> roleCodes = new ArrayList<>(users.activeGrants(userId).stream().map(RoleGrant::role).toList());
        roleCodes.addAll(extraRoles);
        List<String> granted = new ArrayList<>(authorization.grantsInForce(userId, clock.instant()).stream()
                .map(SpecialGrant::permission).toList());
        granted.addAll(extraPermissions);
        return SeparationOfDuties.conflicting(EffectivePermissions.permissions(roleCodes, roles(), granted));
    }

    Map<String, RoleDefinition> roles() {
        return authorization.roles().stream().collect(Collectors.toMap(RoleDefinition::code, Function.identity()));
    }
}
