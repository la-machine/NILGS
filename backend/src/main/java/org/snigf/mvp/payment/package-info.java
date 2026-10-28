// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * DEMO fee schedules, payment requests, simulated provider callbacks and reconciliation (MVP-08,
 * E-17, ADR-037). A payment request is not a confirmed payment, and no real money ever moves: the
 * only provider is the DEMO simulator, behind a replaceable provider port.
 *
 * <p>It implements the application module's {@code PaymentLookup}, which the completeness review
 * asks before acceptance. It publishes nothing of its own; sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Payments")
package org.snigf.mvp.payment;

import org.springframework.modulith.ApplicationModule;
