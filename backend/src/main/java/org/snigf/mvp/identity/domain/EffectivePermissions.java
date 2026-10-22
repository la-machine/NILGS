// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.snigf.mvp.identity.ActiveGrant;
import org.snigf.mvp.identity.RoleGrant;
import org.snigf.mvp.shared.security.Permission;
import org.snigf.mvp.shared.security.Permission.Scope;

/**
 * What a user may do (ADR-025): the permissions of their active roles, plus their special
 * grants in force.
 *
 * <p>Role permissions become authorities:
 * <ul>
 *   <li>scope ANY: {@code audit.event.search}, held everywhere;</li>
 *   <li>scope OFFICE: {@code parcel.create@DEMO1}, held at the office of the role grant;</li>
 *   <li>ASSIGNED, OWN, LINKED: the bare code; the service checks the record itself.</li>
 * </ul>
 * Special grants stay separate ({@link ActiveGrant}), because each use is counted and
 * audited when an action relies on one.
 */
public final class EffectivePermissions {

    private EffectivePermissions() {
    }

    public static Set<String> authorities(Set<RoleGrant> grants, Map<String, RoleDefinition> roles) {
        Set<String> authorities = new TreeSet<>();
        for (RoleGrant grant : grants) {
            RoleDefinition role = roles.get(grant.role());
            if (role == null || !role.active()) {
                continue;
            }
            for (Map.Entry<Permission, Scope> entry : role.catalogued().entrySet()) {
                if (entry.getValue() == Scope.OFFICE) {
                    if (grant.officeCode() != null) {
                        authorities.add(entry.getKey().at(grant.officeCode()));
                    }
                } else {
                    authorities.add(entry.getKey().code());
                }
            }
        }
        return authorities;
    }

    /** Every catalogue permission a user would hold, whatever the scope (for separation of duties). */
    public static Set<Permission> permissions(Collection<String> roleCodes, Map<String, RoleDefinition> roles,
                                              List<String> grantedPermissions) {
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        for (String code : roleCodes) {
            RoleDefinition role = roles.get(code);
            if (role != null && role.active()) {
                held.addAll(role.catalogued().keySet());
            }
        }
        grantedPermissions.forEach(code -> Permission.ofCode(code).ifPresent(held::add));
        return held;
    }
}
