// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Procedures A (immatriculation directe) and B (mutation totale) between intake and decision (Slice 4, ADR-042): the
 * stage records of a case's procedure, each by the officer the stage names, with its evidence; corrections as new
 * records naming what they replace, never overwriting; the gate that holds a case's approval until every stage is
 * completed. DEMO only: no stage here is a validated legal step.
 *
 * <p>Every write is a database function (V32), which checks the actor itself; the review module's gate is a trigger
 * on its own tables. A stage record is not a decision, a registration, a title document or a delivery. Sub-packages
 * are internal (ADR-001).
 */
@ApplicationModule(displayName = "Procedures")
package org.snigf.mvp.procedure;

import org.springframework.modulith.ApplicationModule;
