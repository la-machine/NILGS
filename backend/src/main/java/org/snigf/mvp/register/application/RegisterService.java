// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.document.DocumentFiles;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.OfficerSignature;
import org.snigf.mvp.identity.OfficerSignatures;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.records.SignedRecord;
import org.snigf.mvp.records.SignedRecords;
import org.snigf.mvp.register.domain.InscriptionCanonical;
import org.snigf.mvp.register.domain.Register;
import org.snigf.mvp.register.domain.RegisterRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The land register's use cases (ADR-040). Every write is one call to a database function, which
 * decides; a refusal becomes a {@link RegisterRefused} with its reason code and is audited on its
 * own, because the refused transaction rolls back. Registering and confirming need the officer's
 * security key: the service reads the canonical bytes from the database, has them signed and sealed,
 * and hands everything back to the database in one transaction. If custody is down, nothing is
 * registered. Retried signing calls on a finished inscription return it, by the same officer only.
 */
@Service
public class RegisterService {

    static final String ENTITY_INSCRIPTION = "INSCRIPTION";
    static final String ENTITY_TRANSACTION = "PENDING_TRANSACTION";
    static final String ENTITY_CONFLICT = "TRANSACTION_CONFLICT";
    static final String ENTITY_TITLE = "LAND_TITLE";
    private static final String PROPOSE_ENDPOINT = "POST /api/v1/register/inscriptions";
    private static final String DEPOSIT_ENDPOINT = "POST /api/v1/register/transactions";

    /** A title with its whole history, as a conservator reads it. */
    public record TitleView(Register.Title title, List<Register.Inscription> inscriptions, List<Register.Transaction> transactions,
                            List<Register.Conflict> conflicts) {
    }

    private final RegisterRepository register;
    private final SignedRecords records;
    private final AuditRecorder audit;
    private final DatabaseActor actor;
    private final RegisterProperties properties;
    private final DocumentFiles files;
    private final RegisterWrites writes;
    private final DepositDocuments documents;

    RegisterService(RegisterRepository register, SignedRecords records, AuditRecorder audit, DatabaseActor actor, RegisterProperties properties,
                    DocumentFiles files, RegisterWrites writes, DepositDocuments documents) {
        this.register = register;
        this.records = records;
        this.audit = audit;
        this.actor = actor;
        this.properties = properties;
        this.files = files;
        this.writes = writes;
        this.documents = documents;
    }

    // --- Reading ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Register.TitleSummary> titles(AuthenticatedUser staff, String officeCode, int page) {
        actor.bind(staff.userId());
        return register.titles(officeCode, properties.pageSize(), offset(page));
    }

    @Transactional(readOnly = true)
    public TitleView title(AuthenticatedUser staff, UUID id) {
        actor.bind(staff.userId());
        Register.Title title = register.title(id).orElseThrow(() -> RegisterRefused.of("TITLE_NOT_FOUND"));
        return new TitleView(title, register.inscriptions(id), register.titleTransactions(id), register.titleConflicts(id));
    }

    @Transactional(readOnly = true)
    public Register.Inscription inscription(AuthenticatedUser staff, UUID id) {
        actor.bind(staff.userId());
        return register.inscription(id).orElseThrow(() -> RegisterRefused.of("INSCRIPTION_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<Register.Inscription> proposals(AuthenticatedUser staff, String officeCode, int page) {
        actor.bind(staff.userId());
        return register.proposals(officeCode, properties.pageSize(), offset(page));
    }

    @Transactional(readOnly = true)
    public List<Register.Party> parties(AuthenticatedUser staff, String officeCode, int page) {
        actor.bind(staff.userId());
        return register.parties(officeCode, properties.pageSize(), offset(page));
    }

    @Transactional(readOnly = true)
    public List<Register.Transaction> transactions(AuthenticatedUser staff, String officeCode, boolean overdueOnly, int page) {
        actor.bind(staff.userId());
        return register.transactions(officeCode, overdueOnly, properties.pageSize(), offset(page));
    }

    @Transactional(readOnly = true)
    public List<Register.Conflict> conflicts(AuthenticatedUser staff, String officeCode, boolean openOnly, int page) {
        actor.bind(staff.userId());
        return register.conflicts(officeCode, openOnly, properties.pageSize(), offset(page));
    }

    @Transactional(readOnly = true)
    public Register.ParcelVersionRef parcelVersion(AuthenticatedUser staff, String officeCode, String parcelReference) {
        actor.bind(staff.userId());
        return register.parcelVersion(officeCode, parcelReference == null ? "" : parcelReference.strip())
                .orElseThrow(() -> RegisterRefused.of("PARCEL_NOT_FOUND"));
    }

    /** Checks a title again: each row against its signed bytes, each signature, the chain, each derived version. */
    @Transactional(readOnly = true)
    public Register.Verification verify(AuthenticatedUser staff, UUID titleId) {
        actor.bind(staff.userId());
        register.title(titleId).orElseThrow(() -> RegisterRefused.of("TITLE_NOT_FOUND"));
        List<String> problems = new ArrayList<>();
        byte[] previous = null;
        int inscriptions = 0;
        int signatures = 0;
        List<Register.Inscription> history = register.inscriptions(titleId).stream()
                .filter(i -> "REGISTERED".equals(i.status())).toList();
        for (Register.Inscription i : history) {
            inscriptions++;
            byte[] canonical = InscriptionCanonical.of(i, previous, i.completesTransactionId() == null
                    ? List.of() : register.boundDocuments(i.completesTransactionId()));
            List<Register.StoredSignature> stored = register.signatures(i.id());
            byte[] chain = concat(previous == null ? new byte[0] : previous, canonical);
            if (stored.isEmpty()) {
                problems.add("inscription " + i.number() + ": no signature");
            }
            for (Register.StoredSignature s : stored) {
                signatures++;
                if (!Arrays.equals(s.canonical(), canonical)) {
                    problems.add("inscription " + i.number() + ": the row differs from what was signed");
                }
                OfficerSignature officer = new OfficerSignature(s.credentialId(), s.publicKeyCose(), s.nonce(), s.authenticatorData(),
                        s.clientData(), s.officerSignature());
                if (!records.verify(new SignedRecord(s.canonical(), officer, Signature.parse(s.serverSignature())))) {
                    problems.add("inscription " + i.number() + ": the " + s.act() + " signature does not verify");
                }
                chain = concat(chain, s.officerSignature(), s.serverSignature().getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = sha256(chain);
            if (i.hash() == null || !i.hash().equals(HexFormat.of().formatHex(hash))) {
                problems.add("inscription " + i.number() + ": the chain hash does not match");
            }
            previous = hash;
        }
        List<Boolean> versions = register.versionChecks(titleId);
        for (int v = 0; v < versions.size(); v++) {
            if (!versions.get(v)) {
                problems.add("version " + (v + 1) + ": the derived state does not recompute");
            }
        }
        int documents = 0;
        for (Register.Transaction t : register.titleTransactions(titleId)) {
            for (Register.DepositDocument d : register.depositDocuments(t.id())) {
                documents++;
                if (!files.intact(d.storageKey(), d.sha256(), d.sizeBytes())) {
                    problems.add("deposit document " + d.id() + ": the file no longer matches its hash");
                }
            }
        }
        return new Register.Verification(titleId, inscriptions, signatures, versions.size(), documents, problems);
    }

    // --- Parties and proposals ------------------------------------------------------------------

    @Transactional
    public UUID createParty(AuthenticatedUser staff, Register.NewParty party) {
        actor.bind(staff.userId());
        UUID office = office(party.officeCode());
        UUID id = guarded(staff, ENTITY_TITLE, null, "CREATE_PARTY", () -> register.createParty(office, party));
        audit.record(AuditEvent.of(AuditAction.REGISTER_PARTY_RECORDED, AuditOutcome.SUCCESS).withEntity("REGISTER_PARTY", id)
                .withDetails(Map.of("office", party.officeCode(), "kind", party.kind())));
        return id;
    }

    /** Proposes an inscription. A retry with the same {@code Idempotency-Key} returns the same one. */
    @Transactional
    public UUID propose(AuthenticatedUser staff, Register.NewInscription proposal, String idempotencyKey) {
        actor.bind(staff.userId());
        String hash = writes.requestHash(idempotencyKey, proposal);
        var earlier = writes.earlier(staff.userId(), idempotencyKey, hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        UUID office = office(proposal.officeCode());
        UUID id = guarded(staff, ENTITY_TITLE, proposal.titleId(), "PROPOSE", () -> register.propose(office, proposal));
        Register.Inscription proposed = register.inscription(id).orElseThrow();
        audit.record(AuditEvent.of(AuditAction.REGISTER_INSCRIPTION_PROPOSED, AuditOutcome.SUCCESS).withEntity(ENTITY_INSCRIPTION, id)
                .withEntityRef(proposed.titleNumber()).withDetails(Map.of("kind", proposal.kind(), "office", proposal.officeCode())));
        writes.remember(staff.userId(), idempotencyKey, PROPOSE_ENDPOINT, hash, id);
        return id;
    }

    // --- Signing -----------------------------------------------------------------------------------

    /** Starts the officer's signature over the inscription as the database encodes it now. */
    @Transactional(readOnly = true)
    public OfficerSignatures.Started beginSigning(AuthenticatedUser staff, UUID inscriptionId) {
        actor.bind(staff.userId());
        byte[] canonical = canonicalOf(inscriptionId);
        try {
            return records.begin(staff.userId(), canonical);
        } catch (OfficerSignatures.Refused e) {
            throw refusedIndependently(staff, ENTITY_INSCRIPTION, inscriptionId, new RegisterRefused(RegisterRefused.Kind.INVALID, "SIGNATURE_NOT_AVAILABLE"));
        }
    }

    @Transactional
    public Register.Inscription sign(AuthenticatedUser staff, UUID id, Register.SigningAct act, String ceremonyId, String responseJson) {
        actor.bind(staff.userId());
        Register.Inscription before = register.inscription(id).orElseThrow(() -> RegisterRefused.of("INSCRIPTION_NOT_FOUND"));
        if (alreadyDone(before, staff, act)) {
            return before;
        }
        byte[] canonical = canonicalOf(id);
        SignedRecord signed;
        try {
            signed = records.seal(ceremonyId, staff.userId(), canonical, responseJson);
        } catch (OfficerSignatures.Refused e) {
            throw refusedIndependently(staff, ENTITY_INSCRIPTION, id, new RegisterRefused(RegisterRefused.Kind.INVALID, "SIGNATURE_INVALID"));
        } catch (KeyServiceException e) {
            throw refusedIndependently(staff, ENTITY_INSCRIPTION, id, new RegisterRefused(RegisterRefused.Kind.UNAVAILABLE, "KEY_CUSTODY_UNAVAILABLE"));
        }
        OfficerSignature o = signed.officer();
        RegisterRepository.SignatureEvidence evidence = new RegisterRepository.SignatureEvidence(o.credentialId(), o.publicKeyCose(), o.nonce(),
                o.authenticatorData(), o.clientDataJson(), o.signature(), signed.server().encoded());
        guarded(staff, ENTITY_INSCRIPTION, id, act.name(), () -> {
            switch (act) {
                case REGISTER -> register.register(id, canonical, evidence);
                case REQUEST_CONFIRMATION -> register.requestConfirmation(id, canonical, evidence);
                case CONFIRM -> register.confirm(id, canonical, evidence);
            }
            return id;
        });
        Register.Inscription after = register.inscription(id).orElseThrow();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", after.kind());
        details.put("office", after.officeCode());
        details.put("credential", o.credentialId());
        if (after.number() != null) {
            details.put("number", after.number());
        }
        AuditAction action = switch (act) {
            case REGISTER -> AuditAction.REGISTER_INSCRIPTION_REGISTERED;
            case REQUEST_CONFIRMATION -> AuditAction.REGISTER_INSCRIPTION_CONFIRMATION_REQUESTED;
            case CONFIRM -> AuditAction.REGISTER_INSCRIPTION_CONFIRMED;
        };
        audit.record(AuditEvent.of(action, AuditOutcome.SUCCESS).withEntity(ENTITY_INSCRIPTION, id).withEntityRef(after.titleNumber())
                .withDetails(details));
        if (act == Register.SigningAct.CONFIRM) {
            audit.record(AuditEvent.of(AuditAction.REGISTER_INSCRIPTION_REGISTERED, AuditOutcome.SUCCESS).withEntity(ENTITY_INSCRIPTION, id)
                    .withEntityRef(after.titleNumber()).withDetails(details));
        }
        return after;
    }

    @Transactional
    public Register.Inscription closeProposal(AuthenticatedUser staff, UUID id, String outcome, String reason) {
        actor.bind(staff.userId());
        guarded(staff, ENTITY_INSCRIPTION, id, outcome, () -> {
            register.closeProposal(id, outcome, reason);
            return id;
        });
        Register.Inscription after = register.inscription(id).orElseThrow();
        audit.record(AuditEvent.of("WITHDRAWN".equals(outcome) ? AuditAction.REGISTER_INSCRIPTION_WITHDRAWN : AuditAction.REGISTER_INSCRIPTION_REJECTED,
                        AuditOutcome.SUCCESS).withEntity(ENTITY_INSCRIPTION, id).withEntityRef(after.titleNumber()).withReason(truncate(reason))
                .withDetails(Map.of("kind", after.kind(), "office", after.officeCode())));
        return after;
    }

    // --- Deposits and conflicts -----------------------------------------------------------------------

    /**
     * Records a deposit at the counter (ADR-040 §4): the conservator is the receiving officer, the
     * notary only a named party. A second deposit on a title is recorded in conflict, never refused.
     */
    @Transactional
    public UUID deposit(AuthenticatedUser staff, Register.NewDeposit deposit, String idempotencyKey) {
        actor.bind(staff.userId());
        String hash = writes.requestHash(idempotencyKey, deposit);
        var earlier = writes.earlier(staff.userId(), idempotencyKey, hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        UUID id = guarded(staff, ENTITY_TITLE, deposit.titleId(), "DEPOSIT", () -> register.deposit(deposit, properties.lockReviewAfter()));
        Register.Transaction t = register.transaction(id).orElseThrow();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", t.kind());
        details.put("channel", t.channel());
        details.put("status", t.status());
        details.put("office", t.officeCode());
        details.put("reviewDueAt", t.reviewDueAt().toString());
        audit.record(AuditEvent.of(AuditAction.REGISTER_TRANSACTION_DEPOSITED, AuditOutcome.SUCCESS).withEntity(ENTITY_TRANSACTION, id)
                .withEntityRef(t.titleNumber()).withDetails(details));
        register.conflictOf(id).ifPresent(conflict -> audit.record(AuditEvent.of(AuditAction.REGISTER_CONFLICT_OPENED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_CONFLICT, conflict).withEntityRef(t.titleNumber()).withDetails(Map.of("office", t.officeCode()))));
        writes.remember(staff.userId(), idempotencyKey, DEPOSIT_ENDPOINT, hash, id);
        return id;
    }

    @Transactional
    public Register.Transaction closeTransaction(AuthenticatedUser staff, UUID id, String outcome, String reason) {
        actor.bind(staff.userId());
        guarded(staff, ENTITY_TRANSACTION, id, outcome, () -> {
            register.closeTransaction(id, outcome, reason);
            return id;
        });
        Register.Transaction t = register.transaction(id).orElseThrow();
        audit.record(AuditEvent.of("WITHDRAWN".equals(outcome) ? AuditAction.REGISTER_TRANSACTION_WITHDRAWN : AuditAction.REGISTER_TRANSACTION_REFUSED,
                        AuditOutcome.SUCCESS).withEntity(ENTITY_TRANSACTION, id).withEntityRef(t.titleNumber()).withReason(truncate(reason))
                .withDetails(Map.of("office", t.officeCode())));
        return t;
    }

    @Transactional
    public Register.Conflict resolveConflict(AuthenticatedUser staff, UUID id, String outcome, String reason, List<String> evidence) {
        actor.bind(staff.userId());
        guarded(staff, ENTITY_CONFLICT, id, "RESOLVE", () -> {
            register.resolveConflict(id, outcome, reason, evidence);
            return id;
        });
        Register.Conflict c = register.conflict(id).orElseThrow();
        audit.record(AuditEvent.of(AuditAction.REGISTER_CONFLICT_RESOLVED, AuditOutcome.SUCCESS).withEntity(ENTITY_CONFLICT, id)
                .withEntityRef(c.titleNumber()).withReason(truncate(reason))
                .withDetails(Map.of("outcome", outcome, "office", c.officeCode())));
        return c;
    }

    // --- A deposit's documents (ADR-040 §4.7) ------------------------------------------------------------

    /** A file to send, already checked against its hash. */
    public record DocumentDownload(Register.DepositDocument document, InputStream content) {
    }

    @Transactional(readOnly = true)
    public List<Register.DepositDocument> depositDocuments(AuthenticatedUser staff, UUID transactionId) {
        actor.bind(staff.userId());
        register.transaction(transactionId).orElseThrow(() -> RegisterRefused.of("TRANSACTION_NOT_FOUND"));
        return register.depositDocuments(transactionId);
    }

    /**
     * Keeps a file with a deposit, through the checks of ADR-033, and records it. It writes that record and
     * nothing else: no inscription, no status, no holder. A replacement names the document it corrects and
     * why, and the original stays. A retry with the same {@code Idempotency-Key} and file adds nothing.
     */
    @Transactional
    public UUID addDocument(AuthenticatedUser staff, UUID transactionId, String category, String givenName, InputStream content,
                            UUID replaces, String reason, String idempotencyKey) {
        actor.bind(staff.userId());
        RegisterWrites.requireKey(idempotencyKey);
        Register.Transaction t = register.transaction(transactionId).orElseThrow(() -> RegisterRefused.of("TRANSACTION_NOT_FOUND"));
        String kept = category;
        if (replaces != null) {
            // A replacement keeps the original's category; another one is refused, as before D19.
            Register.DepositDocument original = register.depositDocument(transactionId, replaces).orElseThrow(() -> RegisterRefused.of("DOCUMENT_NOT_FOUND"));
            if (category != null && !category.equals(original.category())) {
                throw refusedIndependently(staff, ENTITY_TRANSACTION, transactionId, new RegisterRefused(RegisterRefused.Kind.INVALID, "CATEGORY_INVALID"));
            }
            kept = original.category();
        }
        return documents.store(staff.userId(), new DepositDocuments.Target(transactionId, t.titleNumber(), t.officeCode()), kept, givenName,
                content, replaces, reason, idempotencyKey);
    }

    /** The bytes of a document, checked against the recorded hash before anything is sent. Audited. */
    @Transactional
    public DocumentDownload downloadDocument(AuthenticatedUser staff, UUID transactionId, UUID documentId) {
        actor.bind(staff.userId());
        Register.DepositDocument d = register.depositDocument(transactionId, documentId).orElseThrow(() -> RegisterRefused.of("DOCUMENT_NOT_FOUND"));
        return new DocumentDownload(d, documents.open(transactionId, d.id(), d.category(), d.storageKey(), d.sha256(), d.sizeBytes()));
    }

    // --- Replacing a deposit's document needs a second person (ADR-041 D19) --------------------------------

    /** A replacement request's file to send, already checked against its hash. */
    public record ReplacementDownload(Register.DocumentReplacement replacement, InputStream content) {
    }

    /** Every request of a deposit: pending, approved, rejected and withdrawn, oldest first. */
    @Transactional(readOnly = true)
    public List<Register.DocumentReplacement> replacements(AuthenticatedUser staff, UUID transactionId) {
        actor.bind(staff.userId());
        register.transaction(transactionId).orElseThrow(() -> RegisterRefused.of("TRANSACTION_NOT_FOUND"));
        return register.replacements(transactionId);
    }

    /**
     * Approves or rejects a pending request. The database refuses the requester, a professional, anyone without
     * {@code register.confirm} at the office, and an approval on a closed deposit. Approval adds the document
     * naming the original, under the title's lock; the original is never changed.
     */
    @Transactional
    public Register.DocumentReplacement decideReplacement(AuthenticatedUser staff, UUID transactionId, UUID replacementId, String outcome, String reason) {
        actor.bind(staff.userId());
        if (!"APPROVED".equals(outcome) && !"REJECTED".equals(outcome)) {
            throw refusedIndependently(staff, ENTITY_TRANSACTION, transactionId, new RegisterRefused(RegisterRefused.Kind.INVALID, "OUTCOME_REQUIRED"));
        }
        return closeReplacement(staff, transactionId, replacementId, outcome, reason);
    }

    /** The requester takes their pending request back; its file is kept. */
    @Transactional
    public Register.DocumentReplacement withdrawReplacement(AuthenticatedUser staff, UUID transactionId, UUID replacementId, String reason) {
        actor.bind(staff.userId());
        return closeReplacement(staff, transactionId, replacementId, "WITHDRAWN", reason);
    }

    private Register.DocumentReplacement closeReplacement(AuthenticatedUser staff, UUID transactionId, UUID replacementId, String outcome, String reason) {
        Register.Transaction t = register.transaction(transactionId).orElseThrow(() -> RegisterRefused.of("TRANSACTION_NOT_FOUND"));
        Register.DocumentReplacement r = register.replacement(replacementId).filter(x -> x.transactionId().equals(transactionId))
                .orElseThrow(() -> RegisterRefused.of("REPLACEMENT_NOT_FOUND"));
        documents.close(new DepositDocuments.Target(transactionId, t.titleNumber(), t.officeCode()), r, outcome, reason);
        return register.replacement(replacementId).orElseThrow();
    }

    /** The file a request carries, checked against its hash. Audited. */
    @Transactional
    public ReplacementDownload downloadReplacement(AuthenticatedUser staff, UUID transactionId, UUID replacementId) {
        actor.bind(staff.userId());
        Register.DocumentReplacement r = register.replacement(replacementId).filter(x -> x.transactionId().equals(transactionId))
                .orElseThrow(() -> RegisterRefused.of("REPLACEMENT_NOT_FOUND"));
        return new ReplacementDownload(r, documents.openReplacement(transactionId, r.documentId(), r.id(), r.storageKey(), r.sha256(), r.sizeBytes()));
    }

    @Transactional(readOnly = true)
    public String officeOf(String recordType, UUID id) {
        return register.officeCodeOf(recordType, id);
    }

    // --- Rules ------------------------------------------------------------------------------------------

    private byte[] canonicalOf(UUID inscriptionId) {
        try {
            return register.canonical(inscriptionId);
        } catch (DataAccessException e) {
            throw translated(e);
        }
    }

    private boolean alreadyDone(Register.Inscription i, AuthenticatedUser staff, Register.SigningAct act) {
        return switch (act) {
            case REQUEST_CONFIRMATION -> "CONFIRMATION_PENDING".equals(i.status()) && staff.userId().equals(i.proposedBy());
            case REGISTER, CONFIRM -> "REGISTERED".equals(i.status()) && staff.userId().equals(i.registeredBy());
        };
    }

    private UUID office(String code) {
        return register.officeId(code == null ? "" : code.strip()).orElseThrow(() -> RegisterRefused.of("OFFICE_NOT_FOUND"));
    }

    /** Runs a write; the database's refusal becomes a precise code and an independent audit event. */
    private UUID guarded(AuthenticatedUser staff, String entity, UUID entityId, String operation, Supplier<UUID> write) {
        return writes.guarded(entity, entityId, operation, write);
    }

    private RegisterRefused refusedIndependently(AuthenticatedUser staff, String entity, UUID id, RegisterRefused refused) {
        return writes.refusedIndependently(entity, id, refused);
    }

    private static RegisterRefused translated(DataAccessException e) {
        return RegisterRefused.translated(e);
    }

    private int offset(int page) {
        if (page < 0 || page > 10_000) {
            throw new RegisterRefused(RegisterRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return page * properties.pageSize();
    }

    static String truncate(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() > 1000 ? stripped.substring(0, 999) + "…" : stripped;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
