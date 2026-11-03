// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.document.DocumentFiles;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.register.domain.Register;
import org.snigf.mvp.register.domain.RegisterRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The professional channel of the register (ADR-041 §6 and §7): the restricted title lookup, deposits
 * made through a professional account, and the professional's own deposits and their files. Every write
 * is one call to a V26 function, which checks the professional, the office and the ownership again.
 * A deposit is valid and attributable or nothing: its files pass the checks of ADR-033 and are kept
 * first, then the transaction and its documents are recorded together; any refusal records nothing,
 * locks nothing, forgets the kept files and is audited. Depositing registers nothing.
 */
@Service
public class ProfessionalDepositService {

    static final int MAX_FILES = 20;
    private static final String ENTITY_TITLE = "LAND_TITLE";
    private static final String ENTITY_TRANSACTION = DepositDocuments.ENTITY_TRANSACTION;
    private static final String DEPOSIT_ENDPOINT = "POST /api/v1/professional/deposits";
    private static final String CHANNEL = "PROFESSIONAL_ACCOUNT";

    /** One file of a deposit, as received. */
    public record Upload(String category, String name, InputStream content) {
    }

    /** A file to send, already checked against its hash. */
    public record DocumentDownload(Register.ProfessionalDocument document, InputStream content) {
    }

    /** What makes two deposit requests the same, for {@code Idempotency-Key}. */
    private record DepositRequest(UUID titleId, String kind, Register.NewDepositor depositor, List<String> files) {
    }

    private final RegisterRepository register;
    private final AuditRecorder audit;
    private final DatabaseActor actor;
    private final RegisterProperties properties;
    private final DocumentFiles files;
    private final RegisterWrites writes;
    private final DepositDocuments documents;
    private final LookupLimiter limiter;

    ProfessionalDepositService(RegisterRepository register, AuditRecorder audit, DatabaseActor actor, RegisterProperties properties, DocumentFiles files,
                               RegisterWrites writes, DepositDocuments documents, LookupLimiter limiter) {
        this.register = register;
        this.audit = audit;
        this.actor = actor;
        this.properties = properties;
        this.files = files;
        this.writes = writes;
        this.documents = documents;
        this.limiter = limiter;
    }

    // --- The lookup (§6) -------------------------------------------------------------------------------

    /**
     * A title at an office the professional serves, and whether it accepts a deposit; empty for an unknown
     * number and for a title elsewhere alike. Audited either way, and throttled per account.
     */
    @Transactional
    public Optional<Register.TitleLookup> lookup(AuthenticatedUser professional, String number) {
        actor.bind(professional.userId());
        if (!limiter.tryAcquire(professional.userId())) {
            throw writes.refusedIndependently(ENTITY_TITLE, null, new RegisterRefused(RegisterRefused.Kind.RATE_LIMITED, "LOOKUP_RATE_LIMITED"));
        }
        String query = number == null ? "" : number.strip();
        Optional<Register.TitleLookup> found = register.lookupTitle(query);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capacity", capacity(professional));
        details.put("found", found.isPresent());
        details.put("query", query.length() > 64 ? query.substring(0, 64) : query);
        AuditEvent event = AuditEvent.of(AuditAction.REGISTER_TITLE_LOOKED_UP, AuditOutcome.SUCCESS);
        if (found.isPresent()) {
            event = event.withEntity(ENTITY_TITLE, found.get().id()).withEntityRef(found.get().number());
            details.put("office", found.get().officeCode());
        }
        audit.record(event.withDetails(details));
        return found;
    }

    // --- Deposits (§7) -----------------------------------------------------------------------------------

    /** Deposits a transaction with its files, a deed among them. A retry with the same {@code Idempotency-Key} returns the same deposit. */
    @Transactional
    public UUID deposit(AuthenticatedUser professional, UUID titleId, String kind, Register.NewDepositor depositor, List<Upload> uploads,
                        String idempotencyKey) {
        actor.bind(professional.userId());
        RegisterWrites.requireKey(idempotencyKey);
        if (uploads == null || uploads.isEmpty() || uploads.size() > MAX_FILES) {
            throw writes.refusedIndependently(ENTITY_TITLE, titleId, RegisterRefused.of("DOCUMENTS_INVALID"));
        }
        if (uploads.stream().noneMatch(u -> "DEED".equals(u.category()))) {
            throw writes.refusedIndependently(ENTITY_TITLE, titleId, RegisterRefused.of("DEED_REQUIRED"));
        }
        List<DocumentFiles.Received> received = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        try {
            for (Upload upload : uploads) {
                try {
                    received.add(files.receive(upload.content(), upload.name()));
                } catch (DocumentFiles.Refused e) {
                    throw documents.refusedFile(ENTITY_TITLE, titleId, e);
                }
            }
            List<String> described = new ArrayList<>();
            for (int i = 0; i < uploads.size(); i++) {
                described.add(uploads.get(i).category() + ":" + received.get(i).sha256() + ":" + received.get(i).name());
            }
            String hash = writes.requestHash(idempotencyKey, new DepositRequest(titleId, kind, depositor, described));
            var earlier = writes.earlier(professional.userId(), idempotencyKey, hash);
            if (earlier.isPresent()) {
                return earlier.get();
            }
            List<Register.KeptDocument> recorded = new ArrayList<>();
            for (int i = 0; i < uploads.size(); i++) {
                DocumentFiles.Received file = received.get(i);
                String key = file.keep();
                kept.add(key);
                recorded.add(new Register.KeptDocument(uploads.get(i).category(), key, file.sha256(), file.sizeBytes(), file.mediaType(), file.name()));
            }
            Register.DepositOutcome outcome = writes.guarded(ENTITY_TITLE, titleId, "DEPOSIT_AS_PROFESSIONAL",
                    () -> register.depositAsProfessional(titleId, kind, depositor, recorded, properties.lockReviewAfter()));
            UUID id = outcome.transactionId();
            Register.ProfessionalDeposit d = own(id);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("kind", d.kind());
            details.put("channel", CHANNEL);
            details.put("account", professional.userId().toString());
            details.put("capacity", d.capacity());
            details.put("status", outcome.conflictId() == null ? "HOLDING" : "IN_CONFLICT");
            details.put("office", d.officeCode());
            details.put("documents", recorded.size());
            audit.record(AuditEvent.of(AuditAction.REGISTER_TRANSACTION_DEPOSITED, AuditOutcome.SUCCESS).withEntity(ENTITY_TRANSACTION, id)
                    .withEntityRef(d.titleNumber()).withDetails(details));
            for (Register.ProfessionalDocument file : register.professionalDocuments(id)) {
                audit.record(AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_ADDED, AuditOutcome.SUCCESS).withEntity(DepositDocuments.ENTITY_DOCUMENT, file.id())
                        .withEntityRef(d.titleNumber()).withDetails(Map.of("transaction", id.toString(), "category", file.category(), "type", file.mediaType(),
                                "sizeBytes", file.sizeBytes(), "sha256", file.sha256(), "office", d.officeCode())));
            }
            if (outcome.conflictId() != null) {
                audit.record(AuditEvent.of(AuditAction.REGISTER_CONFLICT_OPENED, AuditOutcome.SUCCESS).withEntity("TRANSACTION_CONFLICT", outcome.conflictId())
                        .withEntityRef(d.titleNumber()).withDetails(Map.of("office", d.officeCode(), "channel", CHANNEL)));
            }
            writes.remember(professional.userId(), idempotencyKey, DEPOSIT_ENDPOINT, hash, id);
            return id;
        } catch (RuntimeException e) {
            // Nothing was recorded: the files kept for it must not stay.
            kept.forEach(files::forget);
            throw e;
        } finally {
            received.forEach(DocumentFiles.Received::close);
        }
    }

    @Transactional(readOnly = true)
    public List<Register.ProfessionalDeposit> deposits(AuthenticatedUser professional, int page) {
        actor.bind(professional.userId());
        if (page < 0 || page > 10_000) {
            throw new RegisterRefused(RegisterRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return register.professionalDeposits(null, properties.pageSize(), page * properties.pageSize());
    }

    @Transactional(readOnly = true)
    public Register.ProfessionalDeposit deposit(AuthenticatedUser professional, UUID id) {
        actor.bind(professional.userId());
        return own(id);
    }

    /**
     * Withdraws the professional's own open deposit, with a reason. Another's does not exist for them. A withdrawal while a
     * conflict naming it is under review is marked in the audit trail, so deposits made to learn whether a title is held
     * can be found (ADR-041 D17).
     */
    @Transactional
    public Register.ProfessionalDeposit withdraw(AuthenticatedUser professional, UUID id, String reason) {
        actor.bind(professional.userId());
        writes.guarded(ENTITY_TRANSACTION, id, "WITHDRAWN", () -> {
            register.closeTransaction(id, "WITHDRAWN", reason);
            return id;
        });
        Register.ProfessionalDeposit d = own(id);
        audit.record(AuditEvent.of(AuditAction.REGISTER_TRANSACTION_WITHDRAWN, AuditOutcome.SUCCESS).withEntity(ENTITY_TRANSACTION, id)
                .withEntityRef(d.titleNumber()).withReason(RegisterService.truncate(reason))
                .withDetails(Map.of("office", d.officeCode(), "channel", CHANNEL, "capacity", d.capacity(),
                        "withdrawnDuringConflict", d.conflictUnderReview())));
        return d;
    }

    // --- Their files ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Register.ProfessionalDocument> documents(AuthenticatedUser professional, UUID transactionId) {
        actor.bind(professional.userId());
        own(transactionId);
        return register.professionalDocuments(transactionId);
    }

    /** Adds or replaces a file on the professional's own open deposit, as a conservator would (ADR-040 §4.7). */
    @Transactional
    public UUID addDocument(AuthenticatedUser professional, UUID transactionId, String category, String givenName, InputStream content, UUID replaces,
                            String reason, String idempotencyKey) {
        actor.bind(professional.userId());
        RegisterWrites.requireKey(idempotencyKey);
        Register.ProfessionalDeposit d = own(transactionId);
        return documents.store(professional.userId(), new DepositDocuments.Target(transactionId, d.titleNumber(), d.officeCode()), category, givenName,
                content, replaces, reason, idempotencyKey);
    }

    @Transactional
    public DocumentDownload download(AuthenticatedUser professional, UUID transactionId, UUID documentId) {
        actor.bind(professional.userId());
        Register.ProfessionalDocument d = register.professionalDocuments(transactionId).stream().filter(x -> x.id().equals(documentId)).findFirst()
                .orElseThrow(() -> RegisterRefused.of("DOCUMENT_NOT_FOUND"));
        return new DocumentDownload(d, documents.open(transactionId, d.id(), d.category(), d.storageKey(), d.sha256(), d.sizeBytes()));
    }

    // --- Rules -------------------------------------------------------------------------------------------------

    private Register.ProfessionalDeposit own(UUID id) {
        return register.professionalDeposits(id, 1, 0).stream().findFirst().orElseThrow(() -> RegisterRefused.of("TRANSACTION_NOT_FOUND"));
    }

    /** The capacity the professional acts in: their profession's role. */
    private static String capacity(AuthenticatedUser professional) {
        return String.join(",", professional.roles().stream().sorted().toList());
    }
}
