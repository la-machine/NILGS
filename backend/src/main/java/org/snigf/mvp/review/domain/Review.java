// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The substantive review of a case and its simulated decision (MVP-05, V16): who is assigned at
 * each stage, the steps (recommendations with their finding, returns with their reason), and the
 * decision. Plain values; the rules are enforced by the service and again by the database.
 */
public final class Review {

    private Review() {
    }

    /** Bounds of a finding, a reason or grounds (V16). */
    public static final int TEXT_MIN = 10;
    public static final int TEXT_MAX = 4000;
    /** Most versions a decision may cite (V16). */
    public static final int MAX_CITED = 50;

    public enum Stage { REVIEW, DECISION }

    public enum Recommendation { RECOMMEND_APPROVAL, RECOMMEND_REFUSAL, RETURN_FOR_INFORMATION }

    public enum Outcome { APPROVED, REFUSED }

    public record Assignment(Stage stage, UUID assigneeId, String assigneeName, Instant at) {
    }

    /** A recommendation (with its finding) or a return to review (with its reason). */
    public record Step(UUID id, String kind, Recommendation recommendation, String text, UUID actorId, String actorName,
                       Instant at) {
    }

    /** Always simulated in the MVP: "Simulated decision — no legal effect". */
    public record Decision(UUID id, Outcome outcome, String grounds, List<UUID> citedVersions, boolean simulated,
                           String decidedByName, Instant at) {

        public Decision {
            citedVersions = List.copyOf(citedVersions);
        }
    }

    /** A case in a reviewer's or decision authority's queue. */
    public record QueueEntry(UUID applicationId, String reference, String state, String parcelReference, String officeCode,
                             String applicantName, Instant updatedAt, String assigneeName, boolean mine) {
    }

    /** Whether a finding, a reason or grounds say something and stay readable. */
    public static boolean textValid(String text) {
        int length = text == null ? 0 : text.strip().length();
        return length >= TEXT_MIN && length <= TEXT_MAX;
    }
}
