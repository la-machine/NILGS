// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Read-only views over other modules: application tracking, workload, stage durations.
 *
 * <p>Delivered from E-16. Other modules use only the public types in this package;
 * sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Reporting")
package org.snigf.mvp.reporting;

import org.springframework.modulith.ApplicationModule;
