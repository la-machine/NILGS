// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.review.application.ReviewService;
import org.snigf.mvp.review.domain.Review;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review and simulated decision (MVP-05, E-15, ADR-035). Reviewers and decision authorities hold
 * their permissions at scope ASSIGNED: the service works on the cases assigned to them or
 * waiting at their stage, as row-level security shows them. Office readers see a case's review
 * at {@code /cases/{id}/review}. A reviewer recording a decision is refused (403): deciding needs
 * {@code decision.record}.
 */
@RestController
class ReviewController {

    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    record RecommendationBody(long expectedVersion, Review.Recommendation recommendation, @Size(max = 8000) String finding) {
    }

    record ReturnBody(long expectedVersion, @Size(max = 8000) String reason) {
    }

    record DecisionBody(long expectedVersion, Review.Outcome outcome, @Size(max = 8000) String grounds,
                        @Size(max = 100) List<UUID> citedVersions) {
    }

    record QueueResponse(UUID id, String reference, String state, String parcelReference, String officeCode,
                         String applicantName, Instant updatedAt, String assigneeName, boolean mine) {
    }

    /** {@code mine}: assigned to the person asking. */
    record AssignmentResponse(String stage, String assigneeName, Instant at, boolean mine) {
    }

    record StepResponse(String kind, String recommendation, String text, String actorName, Instant at) {
    }

    /** {@code simulated} is always true: "Simulated decision — no legal effect". */
    record DecisionResponse(String outcome, String grounds, List<UUID> citedVersions, boolean simulated, String decidedByName,
                            Instant at) {
    }

    /**
     * @param openAlerts overlap and duplicate alerts still open on the parcel: a decision waits for them
     */
    /**
     * @param youRecommended whether the person asking recommended on this case (then never decides it)
     */
    record ReviewResponse(UUID id, String reference, String state, long version, String officeCode,
                          List<AssignmentResponse> assignments, List<StepResponse> steps, DecisionResponse decision,
                          int openAlerts, boolean youRecommended) {
    }

    @GetMapping("/api/v1/reviews")
    @PreAuthorize("@access.canAssigned(authentication, 'application.read')")
    List<QueueResponse> queue(@AuthenticationPrincipal AuthenticatedUser staff, @RequestParam Review.Stage stage,
                              @RequestParam(defaultValue = "false") boolean mine, @RequestParam(defaultValue = "0") int page) {
        return reviews.queue(staff, stage, mine, page).stream()
                .map(q -> new QueueResponse(q.applicationId(), q.reference(), q.state(), q.parcelReference(), q.officeCode(),
                        q.applicantName(), q.updatedAt(), q.assigneeName(), q.mine()))
                .toList();
    }

    @GetMapping("/api/v1/reviews/{id}")
    @PreAuthorize("@access.canAssigned(authentication, 'application.read')")
    ReviewResponse read(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return response(reviews.view(staff, id), staff);
    }

    @GetMapping("/api/v1/cases/{id}/review")
    @PreAuthorize("@access.canAt(authentication, 'application.read', @caseOffices.of(#id))")
    ReviewResponse readForOffice(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return response(reviews.view(staff, id), staff);
    }

    @PostMapping("/api/v1/reviews/{id}/review-claim")
    @PreAuthorize("@access.canAssigned(authentication, 'review.recommend')")
    ReviewResponse claimReview(@AuthenticationPrincipal AuthenticatedUser reviewer, @PathVariable UUID id) {
        return response(reviews.claim(reviewer, id, Review.Stage.REVIEW), reviewer);
    }

    @PostMapping("/api/v1/reviews/{id}/decision-claim")
    @PreAuthorize("@access.canAssigned(authentication, 'decision.record')")
    ReviewResponse claimDecision(@AuthenticationPrincipal AuthenticatedUser authority, @PathVariable UUID id) {
        return response(reviews.claim(authority, id, Review.Stage.DECISION), authority);
    }

    @PostMapping("/api/v1/reviews/{id}/recommendation")
    @PreAuthorize("@access.canAssigned(authentication, 'review.recommend')")
    ReviewResponse recommend(@AuthenticationPrincipal AuthenticatedUser reviewer, @PathVariable UUID id,
                             @Valid @RequestBody RecommendationBody body) {
        return response(reviews.recommend(reviewer, id, body.expectedVersion(), body.recommendation(), body.finding()), reviewer);
    }

    @PostMapping("/api/v1/reviews/{id}/return")
    @PreAuthorize("@access.canAssigned(authentication, 'decision.record')")
    ReviewResponse returnToReview(@AuthenticationPrincipal AuthenticatedUser authority, @PathVariable UUID id,
                                  @Valid @RequestBody ReturnBody body) {
        return response(reviews.returnToReview(authority, id, body.expectedVersion(), body.reason()), authority);
    }

    @PostMapping("/api/v1/reviews/{id}/decision")
    @PreAuthorize("@access.canAssigned(authentication, 'decision.record')")
    ReviewResponse decide(@AuthenticationPrincipal AuthenticatedUser authority, @PathVariable UUID id,
                          @Valid @RequestBody DecisionBody body) {
        return response(reviews.decide(authority, id, body.expectedVersion(), body.outcome(), body.grounds(),
                body.citedVersions()), authority);
    }

    private static ReviewResponse response(ReviewService.ReviewView view, AuthenticatedUser viewer) {
        return new ReviewResponse(view.state().id(), view.state().reference(), view.state().state(), view.state().version(),
                view.state().officeCode(),
                view.assignments().stream().map(a -> new AssignmentResponse(a.stage().name(), a.assigneeName(), a.at(),
                        a.assigneeId().equals(viewer.userId()))).toList(),
                view.steps().stream().map(s -> new StepResponse(s.kind(),
                        s.recommendation() == null ? null : s.recommendation().name(), s.text(), s.actorName(), s.at())).toList(),
                view.decision().map(d -> new DecisionResponse(d.outcome().name(), d.grounds(), d.citedVersions(), d.simulated(),
                        d.decidedByName(), d.at())).orElse(null),
                view.openAlerts(),
                view.steps().stream().anyMatch(st -> st.kind().equals("RECOMMENDATION") && st.actorId().equals(viewer.userId())));
    }
}
