// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assignments, review steps and decisions under row-level security (V16): only what the actor
 * named for the transaction may see exists, and every write is checked by the database.
 */
public interface ReviewRepository {

    /** The case's current review round (V36): 0 for the original examination, then one per reconsideration. */
    int round(UUID applicationId);

    /** Who is assigned at each stage in the current round. */
    List<Review.Assignment> assignments(UUID applicationId);

    /** Every step, of every round, oldest first. */
    List<Review.Step> steps(UUID applicationId);

    /** The current round's decision, if made. */
    Optional<Review.Decision> decision(UUID applicationId);

    /** The decisions of earlier rounds, oldest first: kept, reconsidered, never changed. */
    List<Review.Decision> earlierDecisions(UUID applicationId);

    /** Overlap and duplicate alerts still open on the application's parcel (MVP-04). */
    int openAlerts(UUID applicationId);

    /** What stops the case's approval under its procedure (ADR-042, V32): empty for a type without one. */
    List<String> procedureGate(UUID applicationId);

    /** Objections to the case's publication still waiting for a decision (ADR-042, V33). */
    int objectionsOpen(UUID applicationId);

    void claim(UUID applicationId, Review.Stage stage, UUID actor);

    UUID addStep(UUID applicationId, String kind, Review.Recommendation recommendation, String text, UUID actor);

    void decide(UUID applicationId, Review.Outcome outcome, String grounds, List<UUID> cited, UUID followsStep, UUID actor);

    /**
     * The actor's queue at a stage: the cases assigned to them ({@code mine}), or those waiting at
     * that stage to be claimed; oldest first.
     */
    List<Review.QueueEntry> queue(Review.Stage stage, boolean mine, UUID actor, int limit, int offset);
}
