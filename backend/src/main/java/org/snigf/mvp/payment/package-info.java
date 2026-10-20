// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * DEMO fee schedules, payment requests, simulated provider callbacks and reconciliation. A payment request is not a confirmed payment.
 *
 * <p>Delivered from E-17 (slice 5). Other modules use only the public types in this package;
 * sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Payments")
package org.snigf.mvp.payment;

import org.springframework.modulith.ApplicationModule;
