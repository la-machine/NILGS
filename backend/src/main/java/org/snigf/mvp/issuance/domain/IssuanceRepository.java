// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The issuance registry (V20). Reads of issued documents run under the actor already named
 * (row-level security: the applicant, staff of the office, the document officer); verification
 * reads its evidence through the database's lookup, which needs no actor and returns nothing personal.
 */
public interface IssuanceRepository {

    /** Serialises issuances of one operation until the end of the transaction. */
    void lockOperation(String operationKey);

    Optional<IssuedDocument> findByOperation(String operationKey);

    Optional<IssuedDocument> findByNumber(String number);

    /** The subject's issued documents of a type, newest first. */
    List<IssuedDocument> ofSubject(UUID subjectId, DocumentType type);

    /** Stores the record and its exact bytes together. */
    UUID insert(IssuedDocument document, String operationKey, UUID supersedes, byte[] pdf);

    Optional<byte[]> artifact(UUID documentId);

    /** Leaves VALID, as the actor named; false when it was no longer valid. */
    boolean changeStatus(UUID documentId, IssuedDocument.Status status, String reason, UUID actor, UUID supersededBy);

    Optional<Verification.Evidence> evidenceByNumber(String number);

    Optional<Verification.Evidence> evidenceBySha(byte[] sha256);

    UUID recordVerification(Verification.Channel channel, String claimedNumber, byte[] presentedSha256,
                            UUID documentId, Verification.Outcome outcome, String reason, UUID actor);

    UUID openIncident(UUID verificationId, Verification.IncidentKind kind, UUID documentId);

    List<Incident> incidents(Incident.Status status, int limit, int offset);

    Optional<Incident> incident(UUID id);

    /** Resolves an open incident at its version, as the actor named; false when it changed meanwhile. */
    boolean resolveIncident(UUID id, long version, Incident.Resolution resolution, String note, UUID actor);

    /**
     * A verification incident (V20).
     *
     * @param documentNumber the issued document concerned; null for a document nothing matched
     */
    record Incident(UUID id, Verification.IncidentKind kind, Status status, Instant openedAt, String documentNumber,
                    Verification.Channel channel, String claimedNumber, String presentedSha256Hex, String reason,
                    Resolution resolution, String note, String resolvedByName, Instant resolvedAt, long version) {

        public enum Status { OPEN, RESOLVED }

        public enum Resolution { CONFIRMED_ALTERATION, GENUINE_COPY, NOT_A_SNIGF_DOCUMENT, REFERRED }
    }
}
