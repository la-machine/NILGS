// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The vocabulary of the land register (ADR-040 §2): what each record says. */
public final class Register {

    private Register() {
    }

    /** Kinds registered only by two conservators, each signing (D9). */
    public static final Set<String> DUAL_CONTROL_KINDS = Set.of("CLOSURE", "CANCELLATION", "CORRECTION");

    /** Which signing act an officer performs. */
    public enum SigningAct { REGISTER, REQUEST_CONFIRMATION, CONFIRM }

    public record Party(UUID id, String kind, String fullName, String identityKind, String identityNumber, String chamber,
                        String registrationNumber, String jurisdiction, UUID accountId) {
    }

    public record NewParty(String officeCode, String kind, String fullName, String identityKind, String identityNumber, String chamber,
                           String registrationNumber, String jurisdiction, UUID accountId) {
    }

    public record Holder(UUID partyId, String partyName, String interest, int shareNum, int shareDen) {
    }

    /** A charge or a restriction; {@code inscriptionId} and {@code number} when it is in force on a title. */
    public record Charge(UUID inscriptionId, Integer number, String kind, UUID beneficiaryId, String beneficiaryName, BigDecimal amount,
                         boolean blocksTransfer, LocalDate endsOn, String note) {
    }

    /** A title as the register says it stands now: every value computed from registered inscriptions. */
    public record Title(UUID id, String number, String officeCode, String origin, Instant createdAt, int version, String status,
                        BigDecimal areaM2, UUID parcelVersionId, String parcelReference, List<Holder> holders, List<Charge> charges,
                        List<Charge> restrictions) {
    }

    public record TitleSummary(UUID id, String number, String officeCode, int version, String status, String holderNames,
                               boolean locked, boolean inConflict) {
    }

    /**
     * One entry. {@code cancelledByNumber} and {@code correctedByNumber} mark an original that stays
     * readable. {@code titleId} is the title it will create while a CREATION is only proposed.
     */
    public record Inscription(UUID id, UUID titleId, UUID officeId, String officeCode, Integer number, String kind, String status, int expectedVersion,
                              String authorityType, String authorityReference, String reason, List<String> evidenceReferences,
                              List<UUID> evidenceVersions, UUID completesTransactionId, UUID targetInscriptionId, BigDecimal areaM2,
                              UUID parcelVersionId, UUID proposedBy, String proposedByName, Instant proposedAt, String confirmedByName,
                              UUID registeredBy, String registeredByName, Instant registeredAt, String closedByName, String closedReason, String hash,
                              List<Holder> holders, Charge charge, Integer cancelledByNumber, Integer correctedByNumber, String titleNumber) {
    }

    public record NewInscription(String officeCode, UUID titleId, String kind, int expectedVersion, String authorityType,
                                 String authorityReference, String reason, List<String> evidenceReferences, List<UUID> evidenceVersions,
                                 UUID completesTransactionId, UUID targetInscriptionId, BigDecimal areaM2, UUID parcelVersionId,
                                 List<NewHolder> holders, NewCharge charge) {
    }

    public record NewHolder(UUID party, String interest, int shareNum, int shareDen) {
    }

    public record NewCharge(String kind, UUID beneficiary, BigDecimal amount, boolean blocksTransfer, LocalDate endsOn, String note) {
    }

    public record NewDeposit(UUID titleId, String kind, UUID depositorPartyId, UUID submittedByPartyId, List<String> evidenceReferences) {
    }

    /** {@code receivedByName} is the officer who authenticated the record; {@code submittedByName} a notary, never an actor. */
    public record Transaction(UUID id, UUID titleId, String titleNumber, String officeCode, String kind, String channel, UUID depositorPartyId,
                              String depositorName, String submittedByName, UUID receivedBy, String receivedByName,
                              List<String> evidenceReferences, String status, Instant depositedAt, Instant reviewDueAt, boolean overdue,
                              String closedReason) {
    }

    public record Conflict(UUID id, UUID titleId, String titleNumber, String officeCode, UUID holdingId, String holdingDepositor,
                           String holdingStatus, UUID arrivingId, String arrivingDepositor, String arrivingStatus, UUID holdingReceivedBy,
                           UUID arrivingReceivedBy, String status, Instant openedAt, String outcome, String resolvedByName, Instant resolvedAt,
                           String resolutionReason) {
    }

    /** One signing act as stored; bytes as they were signed. */
    public record StoredSignature(UUID inscriptionId, String act, UUID signerId, String signerName, byte[] canonical, String credentialId,
                                  byte[] publicKeyCose, byte[] nonce, byte[] authenticatorData, byte[] clientData, byte[] officerSignature,
                                  String serverSignature, Instant signedAt) {
    }

    /** A title's chain, signatures and derived versions, checked again. {@code problems} empty means intact. */
    public record Verification(UUID titleId, int inscriptionsChecked, int signaturesChecked, int versionsChecked, int documentsChecked, List<String> problems) {

        public boolean intact() {
            return problems.isEmpty();
        }
    }

    /** The version a title would rest on. */
    public record ParcelVersionRef(UUID versionId, int versionNo, BigDecimal areaM2) {
    }

    /**
     * One file of a deposit. {@code replacedById} marks an original that a correction replaced: it stays
     * readable. Storing one changes nothing in the register.
     */
    public record DepositDocument(UUID id, UUID transactionId, String category, String storageKey, String sha256, long sizeBytes, String mediaType,
                                  String originalName, UUID uploadedBy, String uploadedByName, Instant uploadedAt, UUID replacesId,
                                  String replaceReason, UUID replacedById) {
    }

    /** What a TRANSFER's signature binds of each document of its deposit. */
    public record BoundDocument(UUID id, String sha256) {
    }

    public record OverdueLock(UUID transactionId, UUID titleId, Instant dueAt) {
    }
}
