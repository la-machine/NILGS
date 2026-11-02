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
