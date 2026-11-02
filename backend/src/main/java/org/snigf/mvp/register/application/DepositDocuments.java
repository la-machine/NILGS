// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.document.DocumentFiles;
import org.snigf.mvp.register.domain.RegisterRepository;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.stereotype.Component;

/**
 * A deposit's files (ADR-040 §4.7), the same for a conservator and for the professional who deposited
 * (ADR-041 §7): the checks of ADR-033, then one call to {@code deposit_document_add}, which decides who
 * may add to which deposit. Storing a file writes that record and nothing else. Not transactional: it
 * runs in the caller's transaction, with the actor already bound.
 */
@Component
class DepositDocuments {

    static final String ENTITY_TRANSACTION = "PENDING_TRANSACTION";
    static final String ENTITY_DOCUMENT = "DEPOSIT_DOCUMENT";
    private static final String DOCUMENT_ENDPOINT = "POST /api/v1/register/transactions/{id}/documents";

    /** The deposit a file goes to, as its reader sees it. */
    record Target(UUID transactionId, String titleNumber, String officeCode) {
    }

    private final RegisterRepository register;
    private final DocumentFiles files;
    private final AuditRecorder audit;
    private final RegisterWrites writes;

    DepositDocuments(RegisterRepository register, DocumentFiles files, AuditRecorder audit, RegisterWrites writes) {
        this.register = register;
        this.files = files;
        this.audit = audit;
        this.writes = writes;
    }

    /** Keeps and records one file; a replacement names what it replaces and why. A retry with the same key and file adds nothing. */
    UUID store(UUID user, Target target, String category, String givenName, InputStream content, UUID replaces, String reason, String idempotencyKey) {
        RegisterWrites.requireKey(idempotencyKey);
        UUID transactionId = target.transactionId();
        String key = null;
        try (DocumentFiles.Received received = files.receive(content, givenName)) {
            String hash = IdempotencyStore.sha256(transactionId + ":" + category + ":" + replaces + ":" + received.sha256() + ":" + received.name());
            var earlier = writes.earlier(user, idempotencyKey, hash);
            if (earlier.isPresent()) {
                return earlier.get();
            }
            key = received.keep();
            String storageKey = key;
            UUID id = writes.guarded(ENTITY_TRANSACTION, transactionId, replaces == null ? "ADD_DOCUMENT" : "REPLACE_DOCUMENT",
                    () -> register.addDepositDocument(transactionId, category, storageKey, received.sha256(), received.sizeBytes(),
                            received.mediaType(), received.name(), replaces, reason));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("transaction", transactionId.toString());
            details.put("category", category);
            details.put("type", received.mediaType());
            details.put("sizeBytes", received.sizeBytes());
            details.put("sha256", received.sha256());
            details.put("office", target.officeCode());
            AuditEvent event = AuditEvent.of(replaces == null ? AuditAction.REGISTER_DEPOSIT_DOCUMENT_ADDED : AuditAction.REGISTER_DEPOSIT_DOCUMENT_REPLACED,
                    AuditOutcome.SUCCESS);
            if (replaces != null) {
                details.put("replaces", replaces.toString());
                event = event.withReason(RegisterService.truncate(reason));
            }
            audit.record(event.withEntity(ENTITY_DOCUMENT, id).withEntityRef(target.titleNumber()).withDetails(details));
            writes.remember(user, idempotencyKey, DOCUMENT_ENDPOINT, hash, id);
            return id;
        } catch (DocumentFiles.Refused e) {
            throw refusedFile(ENTITY_TRANSACTION, transactionId, e);
        } catch (RuntimeException e) {
            // The transaction rolls back: a file stored for it must not stay.
            if (key != null) {
                files.forget(key);
            }
            throw e;
        }
    }

    /** A file refused by the checks of ADR-033, audited on its own; nothing was kept. */
    RegisterRefused refusedFile(String entity, UUID entityId, DocumentFiles.Refused e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", e.code());
        if (ENTITY_TRANSACTION.equals(entity)) {
            details.put("transaction", entityId.toString());
        }
        if (e.sizeBytes() >= 0) {
            details.put("sizeBytes", e.sizeBytes());
        }
        if (e.detected() != null) {
            details.put("detected", e.detected());
        }
        audit.recordIndependently(AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_REJECTED, AuditOutcome.FAILED)
                .withEntity(entity, entityId).withReason(e.code()).withDetails(details));
        return new RegisterRefused("FILE_TOO_LARGE".equals(e.code()) ? RegisterRefused.Kind.TOO_LARGE : RegisterRefused.Kind.INVALID, e.code());
    }

    /** The bytes of a recorded file, checked against its hash before anything is sent. Audited. */
    InputStream open(UUID transactionId, UUID documentId, String category, String storageKey, String sha256, long sizeBytes) {
        if (!files.intact(storageKey, sha256, sizeBytes)) {
            audit.recordIndependently(AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_INTEGRITY_FAILED, AuditOutcome.FAILED)
                    .withEntity(ENTITY_DOCUMENT, documentId).withReason("SHA256_MISMATCH").withDetails(Map.of("transaction", transactionId.toString())));
            throw new RegisterRefused(RegisterRefused.Kind.BROKEN, "DOCUMENT_INTEGRITY_FAILED");
        }
        audit.record(AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_DOWNLOADED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_DOCUMENT, documentId).withDetails(Map.of("transaction", transactionId.toString(), "category", category)));
        return files.open(storageKey);
    }
}
