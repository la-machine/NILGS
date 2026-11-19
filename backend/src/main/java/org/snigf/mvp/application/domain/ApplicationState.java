// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The nine states of an application (Baseline, application lifecycle) and the only legal moves
 * between them; any other is refused ({@code 409 INVALID_TRANSITION}), here and by the database
 * (V13 and V36, {@code application_transition_legal}). A case ends only by decision or withdrawal; an approved one is
 * reopened only by a reconsideration (V36).
 */
public enum ApplicationState {
    DRAFT, SUBMITTED, COMPLETENESS_REVIEW, INFO_REQUESTED, UNDER_REVIEW, PENDING_DECISION, APPROVED, REFUSED, WITHDRAWN;

    private static final Map<ApplicationState, Set<ApplicationState>> NEXT = Map.of(
            DRAFT, EnumSet.of(SUBMITTED),
            SUBMITTED, EnumSet.of(COMPLETENESS_REVIEW, WITHDRAWN),
            COMPLETENESS_REVIEW, EnumSet.of(INFO_REQUESTED, UNDER_REVIEW, WITHDRAWN),
            INFO_REQUESTED, EnumSet.of(COMPLETENESS_REVIEW, WITHDRAWN),
            UNDER_REVIEW, EnumSet.of(PENDING_DECISION, WITHDRAWN),
            PENDING_DECISION, EnumSet.of(UNDER_REVIEW, APPROVED, REFUSED, WITHDRAWN),
            // Only through a reconsideration (V36, ADR-043 step 5b; ADR-032 amended): never a move of the workflow.
            APPROVED, EnumSet.of(UNDER_REVIEW),
            REFUSED, EnumSet.noneOf(ApplicationState.class),
            WITHDRAWN, EnumSet.noneOf(ApplicationState.class));

    public boolean canMoveTo(ApplicationState next) {
        return NEXT.get(this).contains(next);
    }

    /**
     * Refused and withdrawn are final. Approved ends the examination, and is reopened only by a reconsideration after an
     * objection upheld after its decision (ADR-043 step 5b).
     */
    public boolean isFinal() {
        return this == APPROVED || NEXT.get(this).isEmpty();
    }
}
