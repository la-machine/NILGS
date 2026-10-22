// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.Objects;

/**
 * One active role of a user, and the office it was granted at ({@code null} for the
 * applicant role, which has no office).
 *
 * @param role       role name, upper snake case
 * @param officeCode office code, or null
 */
public record RoleGrant(String role, String officeCode) {

    public RoleGrant {
        Objects.requireNonNull(role, "role");
    }
}
