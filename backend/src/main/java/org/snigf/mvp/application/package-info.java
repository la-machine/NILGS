// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Service requests and their lifecycle: explicit states, server-validated transitions, append-only transition history.
 *
 * <p>Delivered from E-12 (slice 3). Other modules use only the public types in this package;
 * sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Applications")
package org.snigf.mvp.application;

import org.springframework.modulith.ApplicationModule;
