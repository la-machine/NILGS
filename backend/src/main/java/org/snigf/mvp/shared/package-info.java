// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Cross-cutting infrastructure every module may use: environment configuration and
 * its startup guard, request correlation, the security baseline.
 * Contains no business rules.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package org.snigf.mvp.shared;

import org.springframework.modulith.ApplicationModule;
