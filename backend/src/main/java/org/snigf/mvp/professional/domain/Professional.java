// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The vocabulary of professional accounts (ADR-041): what each record says. */
public final class Professional {

    private Professional() {
    }

    public static final Set<String> PROFESSIONS = Set.of("NOTARY", "SWORN_SURVEYOR");

    /** Changes a second administrator decides; ACTIVATION is opened by the verification itself. */
    public static final Set<String> CHANGE_KINDS = Set.of("REINSTATEMENT", "REVOCATION", "OFFICE_CHANGE");

    public record NewRequest(String email, String displayName, String password, String preferredLanguage, String profession, String chamber,
                             String registrationNumber, String jurisdiction) {

        @Override
        public String toString() {
            return "NewRequest[<redacted>]";
        }
    }

    public record Profile(UUID userId, String email, String displayName, String profession, String chamber, String registrationNumber,
                          String jurisdiction, String status, Instant requestedAt, Instant statusChangedAt, List<String> offices) {
    }

    /** What the administrators' directory filters on (D20); each is optional, and they combine with AND. */
    public static final Set<String> STATUSES = Set.of("REQUESTED", "VERIFIED", "ACTIVE", "SUSPENDED", "REVOKED");

    /** A waiting step of the lifecycle, for the directory's filter and the dashboard's links (D20, D21). */
    public static final Set<String> PENDING_STEPS = Set.of("VERIFICATION", "ACTIVATION", "CHANGE");

    /**
     * The directory's filters (D20), each checked by the service before it reaches the database. {@code office} is an
     * office where the account is approved or proposed; {@code text} is searched in the e-mail, the display name, the
     * chamber and the registration number, ignoring case and accents.
     */
    public record DirectoryFilter(String status, String profession, String office, String pending, String text) {
    }

    /** One page of the directory, newest account first, and how many accounts match in all. */
    public record DirectoryPage(List<Profile> items, long total, int page, int size) {
    }

    /**
     * What waits on the dashboard (D21), pending items only. A count is null when the viewer may not act on it, so it is
     * left out rather than sent as zero. {@code requests}: accounts waiting for a verification; {@code activations}: pending
     * activations the viewer neither verified nor requested; {@code changes}: pending reinstatements, revocations and
     * office changes the viewer did not request; {@code waitingForOthers}: the viewer's own pending requests, which only
     * another administrator may decide.
     */
    public record Counts(Long requests, Long activations, Long changes, Long waitingForOthers) {
    }

    public record Request(UUID id, UUID userId, String professionalName, String kind, List<String> officesAdd, List<String> officesRemove,
                          String reason, UUID requestedBy, String requestedByName, Instant requestedAt, String status, String decidedByName,
                          Instant decidedAt, String decisionReason) {
    }

    public record Evidence(UUID id, String storageKey, String sha256, long sizeBytes, String mediaType, String originalName,
                           String uploadedByName, Instant uploadedAt) {
    }

    public record Verification(UUID id, UUID verifiedBy, String verifiedByName, String body, String reference, LocalDate checkedOn, String note,
                               Instant verifiedAt, List<Evidence> documents) {
    }

    public record HistoryEntry(String event, String actorName, String reason, Instant at) {
    }

    public record NewVerification(String body, String reference, LocalDate checkedOn, String note, List<String> officeCodes) {
    }

    public record NewChange(String kind, List<String> officesAdd, List<String> officesRemove, String reason) {
    }
}
