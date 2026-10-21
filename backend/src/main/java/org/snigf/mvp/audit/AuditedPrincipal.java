// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.Set;
import java.util.UUID;

/**
 * Implemented by the authenticated principal (identity module), so audit events name the
 * user and the roles they held. An authenticated principal that does not implement it is
 * refused rather than recorded as anonymous.
 */
public interface AuditedPrincipal {

    UUID userId();

    /** Role names held for this request, upper snake case. */
    Set<String> auditRoles();
}
