// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review.application;

import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.application.DecisionLookup;
import org.snigf.mvp.review.domain.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DecisionLookup} for the applicant's view: the decision only (outcome, grounds,
 * simulated, date), under the actor the application module already named; never the review.
 */
@Service
public class DecisionLookupService implements DecisionLookup {

    private final ReviewRepository reviews;

    public DecisionLookupService(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DecisionSummary> of(UUID applicationId) {
        return reviews.decision(applicationId)
                .map(d -> new DecisionSummary(d.outcome().name(), d.grounds(), d.simulated(), d.at()));
    }
}
