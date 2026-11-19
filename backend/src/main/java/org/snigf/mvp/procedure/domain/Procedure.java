// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The values of a case's procedure (ADR-042, V31 and V32). */
public final class Procedure {

    private Procedure() {
    }

    /** The two outcomes of a stage record; {@code NOT_SATISFIED} says why in its finding. */
    public static final List<String> OUTCOMES = List.of("COMPLETED", "NOT_SATISFIED");

    /**
     * The case a procedure belongs to, as its readers see it; {@code version} for a reconsideration's optimistic check,
     * {@code round} its current review round (V36).
     */
    public record CaseHeader(UUID id, String reference, String state, String typeCode, String officeCode, long version, int round) {
    }

    /**
     * A reconsideration of the approved case (V36, ADR-043 step 5b): the round it opened, the decision it reconsiders and
     * that decision's outcome, the objections it answers (their numbers), the stages reopened (and which the supervisor
     * added), the justification, who opened it and when, and the outcome of its round's decision once made.
     */
    public record Reconsideration(UUID id, int round, UUID reconsideredDecision, String reconsideredOutcome, List<String> objectionNumbers,
                                  List<String> stages, List<String> addedStages, String justification, UUID openedById, String openedByName,
                                  Instant openedAt, String roundOutcome) {
    }

    /** A stage of the case's procedure, from the catalogue. */
    public record StageDefinition(String code, String procedure, int rank, String requires, String permission, String labelEn,
                                  String labelFr) {
    }

    /** A file an officer attached to a stage record. */
    public record StageFile(UUID id, String originalName, String mediaType, long sizeBytes, String sha256, Instant uploadedAt) {
    }

    /**
     * One record of a stage. {@code supersedesId} names the record it corrects, with {@code correctionReason}; the
     * records of a stage form a chain, the latest being the one nothing supersedes.
     */
    public record StageRecord(UUID id, String stage, String outcome, String finding, Map<String, Object> details,
                              List<UUID> evidenceVersions, UUID supersedesId, String correctionReason, UUID recordedById,
                              String recordedByName, Instant recordedAt, List<StageFile> files) {
    }

    /** What an officer records; the database adds what it knows itself (parcel version, the visit reported on). */
    public record NewRecord(String outcome, String finding, Map<String, Object> details, List<UUID> evidence, UUID supersedes,
                            String correctionReason) {
    }

    /** A file kept for a record, before the record is written. */
    public record KeptFile(String storageKey, String sha256, long sizeBytes, String mediaType, String name) {
    }

    /** An attached file, found for download. */
    public record FileRef(UUID id, UUID recordId, String stage, String storageKey, String sha256, long sizeBytes, String mediaType,
                          String originalName) {
    }

    /** A stage an officer may record, waiting on a case under review at their office. */
    public record QueueEntry(UUID applicationId, String reference, String officeCode, String typeCode, String stage, String stageStatus,
                             Instant submittedAt) {
    }

    /** The two outcomes of an objection's decision; an upheld one names the stages examined again. */
    public static final List<String> OBJECTION_OUTCOMES = List.of("UPHELD", "DISMISSED");

    /** The interests an objector may state at the counter. */
    public static final List<String> INTERESTS = List.of("NEIGHBOUR", "CLAIMANT", "HEIR", "CUSTOMARY_AUTHORITY", "OTHER");

    /**
     * A notice a publication opened (V33). {@code replacesId} names the notice of the publication it corrects; the
     * current notice is the one of the latest publication record.
     */
    public record Notice(UUID id, String number, UUID recordId, String parcelReference, String locality, String period, Instant opensAt,
                         Instant closesAt, UUID replacesId) {
    }

    /** A notice as the public reads it: what notification needs, never the applicant, the case or an objection. */
    public record PublicNotice(String number, String procedure, String parcelReference, String locality, String officeCode,
                               String officeNameFr, String officeNameEn, Instant opensAt, Instant closesAt) {
    }

    /** The person objecting at the counter (synthetic data only). */
    public record Objector(String name, String identityKind, String identityNumber, String interest) {
    }

    /** A file received with an objection. */
    public record ObjectionFile(UUID id, String originalName, String mediaType, long sizeBytes, String sha256, Instant uploadedAt) {
    }

    /**
     * The decision on an objection, once; {@code reviewStages} are empty for a dismissal. {@code applicantInstructions}:
     * what an upheld one asks of the applicant, the only part the applicant reads.
     */
    public record ObjectionDecision(String outcome, String grounds, List<String> reviewStages, String applicantInstructions, UUID decidedById,
                                    String decidedByName, Instant decidedAt) {
    }

    /**
     * An objection as a case's reader sees it: never the objector's identity. {@code identityVisible} says whether this
     * reader may ask for it ({@link ObjectorIdentity}, one objection at a time, each read audited).
     */
    public record Objection(UUID id, String number, String noticeNumber, String objectorName, String interest, boolean identityVisible,
                            String statement, Instant receivedAt, boolean late, UUID recordedById,
                            String recordedByName, ObjectionDecision decision, boolean mayDecide, List<ObjectionFile> files) {
    }

    /** An objector's identity document, for the officers who receive or decide objections at the office. */
    public record ObjectorIdentity(String identityKind, String identityNumber) {
    }

    /** What the officer at the counter records. */
    public record NewObjection(Objector objector, String statement) {
    }

    /** An objection written, or the one a retry with the same key gives back. */
    public record ObjectionWritten(UUID id, boolean replayed) {
    }

    /** A file received with an objection, found for download. */
    public record ObjectionFileRef(UUID id, UUID objectionId, String storageKey, String sha256, long sizeBytes, String mediaType,
                                   String originalName) {
    }

    /** An open objection a supervisor may decide. */
    /**
     * A procedure B case's open deposit (ADR-042 step 6): its status, channel, title and duty receipts, the ones a
     * conservator may cite in DUTIES_EVIDENCE (D24). Never the deed, the depositor or the other documents.
     */
    public record CaseDeposit(UUID id, String status, String channel, Instant depositedAt, String titleNumber, List<Receipt> receipts) {
    }

    public record Receipt(UUID id, String name) {
    }

    public record ObjectionQueueEntry(UUID objectionId, String number, UUID applicationId, String reference, String officeCode,
                                      Instant receivedAt, boolean late) {
    }
}
