// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Audit use cases: record an event, verify the chain, sign a checkpoint. They implement
 * the module's published interfaces ({@code AuditRecorder}, {@code AuditTrail}), own the
 * transaction boundaries, and reach the outside world only through the domain repositories
 * and the ports declared here ({@link org.snigf.mvp.audit.application.CheckpointArchive},
 * {@link org.snigf.mvp.audit.application.AuditRequestContext},
 * {@link org.snigf.mvp.audit.application.AuditChainMonitor}). Never depends on
 * {@code infrastructure} ({@code DddLayeringTest}).
 */
package org.snigf.mvp.audit.application;
