// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The register's data: reads under row-level security, writes only through the functions of V21,
 * which name the actor bound to the transaction. A refusal surfaces as the database's RG001.
 */
public interface RegisterRepository {

    /** The base64 evidence of a signing act, as the database functions take it. */
    record SignatureEvidence(String credentialId, byte[] publicKeyCose, byte[] nonce, byte[] authenticatorData, byte[] clientData,
                             byte[] officerSignature, String serverSignature) {
    }

    Optional<UUID> officeId(String officeCode);

    List<Register.TitleSummary> titles(String officeCode, int limit, int offset);

    Optional<Register.Title> title(UUID id);

    List<Register.Inscription> inscriptions(UUID titleId);

    Optional<Register.Inscription> inscription(UUID id);

    List<Register.Inscription> proposals(String officeCode, int limit, int offset);

    List<Register.Party> parties(String officeCode, int limit, int offset);

    /** The current version of a parcel at this office. */
    Optional<Register.ParcelVersionRef> parcelVersion(String officeCode, String parcelReference);

    UUID createParty(UUID officeId, Register.NewParty party);

    UUID propose(UUID officeId, Register.NewInscription proposal);

    /** The bytes the database will accept a signature over, for whoever may read the inscription. */
    byte[] canonical(UUID inscriptionId);

    void register(UUID inscriptionId, byte[] canonical, SignatureEvidence evidence);

    void requestConfirmation(UUID inscriptionId, byte[] canonical, SignatureEvidence evidence);

    void confirm(UUID inscriptionId, byte[] canonical, SignatureEvidence evidence);

    void closeProposal(UUID inscriptionId, String outcome, String reason);

    UUID deposit(Register.NewDeposit deposit, Duration reviewAfter);

    void closeTransaction(UUID transactionId, String outcome, String reason);

    Optional<Register.Transaction> transaction(UUID id);

    List<Register.Transaction> transactions(String officeCode, boolean overdueOnly, int limit, int offset);

    List<Register.Transaction> titleTransactions(UUID titleId);

    Optional<UUID> conflictOf(UUID arrivingTransactionId);

    void resolveConflict(UUID conflictId, String outcome, String reason, List<String> evidence);

    Optional<Register.Conflict> conflict(UUID id);

    List<Register.Conflict> conflicts(String officeCode, boolean openOnly, int limit, int offset);

    List<Register.Conflict> titleConflicts(UUID titleId);

    /** Records a stored file through the database's function; a replacement names what it replaces. */
    UUID addDepositDocument(UUID transactionId, String category, String storageKey, String sha256, long sizeBytes, String mediaType, String name,
                            UUID replaces, String reason);

    List<Register.DepositDocument> depositDocuments(UUID transactionId);

    Optional<Register.DepositDocument> depositDocument(UUID transactionId, UUID documentId);

    /** The documents a TRANSFER's signature binds, by id. */
    List<Register.BoundDocument> boundDocuments(UUID transactionId);

    List<Register.StoredSignature> signatures(UUID inscriptionId);

    /** The stored chain hash of one registered inscription, as bytes. */
    Optional<byte[]> hashOf(UUID inscriptionId);

    /** Whether each derived version recomputes from the inscriptions. */
    List<Boolean> versionChecks(UUID titleId);

    /** Records every lock past its review date once, and returns them. */
    List<Register.OverdueLock> recordOverdueLocks();

    String officeCodeOf(String recordType, UUID id);
}
