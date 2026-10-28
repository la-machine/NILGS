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

    List<Review.Assignment> assignments(UUID applicationId);

    List<Review.Step> steps(UUID applicationId);

    Optional<Review.Decision> decision(UUID applicationId);

    /** Overlap and duplicate alerts still open on the application's parcel (MVP-04). */
    int openAlerts(UUID applicationId);

    void claim(UUID applicationId, Review.Stage stage, UUID actor);

    UUID addStep(UUID applicationId, String kind, Review.Recommendation recommendation, String text, UUID actor);

    void decide(UUID applicationId, Review.Outcome outcome, String grounds, List<UUID> cited, UUID followsStep, UUID actor);

    /**
     * The actor's queue at a stage: the cases assigned to them ({@code mine}), or those waiting at
     * that stage to be claimed; oldest first.
     */
    List<Review.QueueEntry> queue(Review.Stage stage, boolean mine, UUID actor, int limit, int offset);
}
