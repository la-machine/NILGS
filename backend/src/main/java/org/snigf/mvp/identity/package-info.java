// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Accounts, authentication, MFA, role assignments scoped to offices, permissions. Creating an account grants no authority over land records.
 *
 * <p>Delivered from E-02/E-03 (slice 1). Other modules use only the public types in this package;
 * sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Identity and access")
package org.snigf.mvp.identity;

import org.springframework.modulith.ApplicationModule;
