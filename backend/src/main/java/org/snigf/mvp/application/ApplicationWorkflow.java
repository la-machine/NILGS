// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.Optional;
import java.util.UUID;

/**
 * The lifecycle of a submitted application, for the modules that act on it (review and
 * decision, ADR-035). The actor is named to the database first: a case is visible only if
 * row-level security shows it to them, and a move is applied only if the database's guard
 * accepts it (V16: each move needs its record, written by the person assigned).
 */
public interface ApplicationWorkflow {

    /** The case as the actor may see it; empty when unknown, a draft, or not visible to them. Call inside a transaction. */
    Optional<CaseState> visible(UUID actor, UUID applicationId);

    /**
     * Moves the state if still at {@code expectedVersion} and {@code from}; false when it changed
     * meanwhile. The database refuses a move its record does not back. Call inside a transaction.
     */
    boolean move(UUID actor, UUID applicationId, long expectedVersion, String from, String to, String reason);

    record CaseState(UUID id, String reference, String state, long version, String officeCode, UUID applicantId) {
    }
}
