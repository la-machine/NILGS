// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Append-only, hash-chained audit events, chain verification and signed checkpoints (docs/audit.md).
 *
 * <p>Delivered from E-02/E-06 (slice 1). Other modules use only the public types in this package;
 * sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Audit")
package org.snigf.mvp.audit;

import org.springframework.modulith.ApplicationModule;
