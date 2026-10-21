// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Append-only, hash-chained audit events, chain verification and signed checkpoints
 * (docs/audit.md, ADR-007).
 *
 * <p>Other modules record events with {@link org.snigf.mvp.audit.AuditRecorder} and verify
 * or checkpoint the chain with {@link org.snigf.mvp.audit.AuditTrail}; sub-packages are
 * internal (ADR-001).
 */
@ApplicationModule(displayName = "Audit")
package org.snigf.mvp.audit;

import org.springframework.modulith.ApplicationModule;
