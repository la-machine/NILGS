// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The decision on an application, as its applicant reads it (ADR-035). The application module
 * publishes this contract and the review module implements it, so applications never depend on
 * reviews. The internal review (recommendation, finding) is never part of it.
 */
public interface DecisionLookup {

    /** The decision on the application, if one was recorded; under the actor already named. */
    Optional<DecisionSummary> of(UUID applicationId);

    /** {@code simulated} is always true in the MVP: "Simulated decision — no legal effect". */
    record DecisionSummary(String outcome, String grounds, boolean simulated, Instant decidedAt) {
    }
}
