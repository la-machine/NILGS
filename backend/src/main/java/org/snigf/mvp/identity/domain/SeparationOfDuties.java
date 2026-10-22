// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.Collection;

import org.snigf.mvp.shared.security.Permission;

/**
 * Permission combinations nobody may hold together, through any mix of roles and special
 * grants (ADR-025): administering accounts and roles, and auditing. Whoever controls the
 * accounts must not also be the one who checks the trail of what was done with them.
 */
public final class SeparationOfDuties {

    private SeparationOfDuties() {
    }

    public static boolean conflicting(Collection<Permission> permissions) {
        boolean administers = permissions.stream().anyMatch(p -> p.family() == Permission.Family.ADMINISTRATION);
        boolean audits = permissions.stream().anyMatch(p -> p.family() == Permission.Family.AUDIT);
        return administers && audits;
    }
}
