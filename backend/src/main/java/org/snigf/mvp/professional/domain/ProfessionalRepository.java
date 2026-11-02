// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Professional accounts' data: reads under row-level security, writes only through the functions of
 * V24, which name the actor bound to the transaction. A refusal surfaces as the database's PR001.
 */
public interface ProfessionalRepository {

    void createProfile(UUID userId, String profession, String chamber, String registrationNumber, String jurisdiction);

    Optional<Professional.Profile> profile(UUID userId);

    List<Professional.Profile> profiles(String status, int limit, int offset);

    List<Professional.Request> requests(UUID userId);

    List<Professional.Request> pendingRequests(int limit, int offset);

    Optional<Professional.Request> request(UUID requestId);

    Optional<Professional.Verification> verification(UUID userId);

    Optional<Professional.Evidence> evidence(UUID userId, UUID documentId);

    List<Professional.HistoryEntry> history(UUID userId);

    /** Office ids for codes; unknown codes are left out, so the caller compares the sizes. */
    List<UUID> officeIds(List<String> codes);

    UUID verify(UUID userId, String body, String reference, java.time.LocalDate checkedOn, String note, List<UUID> offices);

    UUID addEvidence(UUID verificationId, String storageKey, String sha256, long sizeBytes, String mediaType, String name);

    void suspend(UUID userId, String reason);

    UUID requestChange(UUID userId, String kind, List<UUID> add, List<UUID> remove, String reason);

    void decide(UUID requestId, boolean approve, String reason);

    /** Closes the request as expired when it is older than {@code ttl}; true when it did. */
    boolean expire(UUID requestId, Duration ttl);
}
