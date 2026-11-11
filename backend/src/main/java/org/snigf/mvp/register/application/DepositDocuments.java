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
import org.snigf.mvp.register.domain.Register;
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
            // D19: a replacement is only a request; the original stays current until a second person approves it.
            UUID id = writes.guarded(ENTITY_TRANSACTION, transactionId, replaces == null ? "ADD_DOCUMENT" : "REQUEST_REPLACEMENT",
                    () -> replaces == null
                            ? register.addDepositDocument(transactionId, category, storageKey, received.sha256(), received.sizeBytes(),
                                    received.mediaType(), received.name(), null, null)
                            : register.requestReplacement(replaces, storageKey, received.sha256(), received.sizeBytes(), received.mediaType(),
                                    received.name(), reason));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("transaction", transactionId.toString());
            details.put("category", category);
            details.put("type", received.mediaType());
            details.put("sizeBytes", received.sizeBytes());
            details.put("sha256", received.sha256());
            details.put("office", target.officeCode());
            AuditEvent event;
            if (replaces == null) {
                event = AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_ADDED, AuditOutcome.SUCCESS).withEntity(ENTITY_DOCUMENT, id);
            } else {
                // The original document is the entity, so the event belongs to its title (ADR-038).
                details.put("replacement", id.toString());
                event = AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_REPLACEMENT_REQUESTED, AuditOutcome.SUCCESS)
                        .withEntity(ENTITY_DOCUMENT, replaces).withReason(RegisterService.truncate(reason));
            }
            audit.record(event.withEntityRef(target.titleNumber()).withDetails(details));
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

    /**
     * The second person's decision on a pending request (D19), or its requester's withdrawal; the database decides
     * who may. Returns the new document on approval, null otherwise. Audited against the original document.
     */
    UUID close(Target target, Register.DocumentReplacement r, String outcome, String reason) {
        UUID transactionId = target.transactionId();
        UUID result = writes.guarded(ENTITY_TRANSACTION, transactionId, "WITHDRAWN".equals(outcome) ? "WITHDRAW_REPLACEMENT" : "DECIDE_REPLACEMENT",
                () -> {
                    if ("WITHDRAWN".equals(outcome)) {
                        register.withdrawReplacement(r.id(), blankToNull(reason));
                        return null;
                    }
                    return register.decideReplacement(r.id(), outcome, blankToNull(reason));
                });
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("transaction", transactionId.toString());
        details.put("replacement", r.id().toString());
        details.put("office", target.officeCode());
        details.put("requestedAs", r.requestedAs());
        AuditAction action = switch (outcome) {
            case "APPROVED" -> AuditAction.REGISTER_DEPOSIT_DOCUMENT_REPLACED;
            case "REJECTED" -> AuditAction.REGISTER_DEPOSIT_DOCUMENT_REPLACEMENT_REJECTED;
            default -> AuditAction.REGISTER_DEPOSIT_DOCUMENT_REPLACEMENT_WITHDRAWN;
        };
        if (result != null) {
            details.put("document", result.toString());
            details.put("sha256", r.sha256());
        }
        AuditEvent event = AuditEvent.of(action, AuditOutcome.SUCCESS).withEntity(ENTITY_DOCUMENT, r.documentId())
                .withEntityRef(target.titleNumber()).withDetails(details);
        if (reason != null && !reason.isBlank()) {
            event = event.withReason(RegisterService.truncate(reason));
        }
        audit.record(event);
        return result;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** The bytes of a replacement request's file, checked against its hash. Audited against the original document. */
    InputStream openReplacement(UUID transactionId, UUID documentId, UUID replacementId, String storageKey, String sha256, long sizeBytes) {
        if (!files.intact(storageKey, sha256, sizeBytes)) {
            audit.recordIndependently(AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_INTEGRITY_FAILED, AuditOutcome.FAILED)
                    .withEntity(ENTITY_DOCUMENT, documentId).withReason("SHA256_MISMATCH")
                    .withDetails(Map.of("transaction", transactionId.toString(), "replacement", replacementId.toString())));
            throw new RegisterRefused(RegisterRefused.Kind.BROKEN, "DOCUMENT_INTEGRITY_FAILED");
        }
        audit.record(AuditEvent.of(AuditAction.REGISTER_DEPOSIT_DOCUMENT_REPLACEMENT_DOWNLOADED, AuditOutcome.SUCCESS)
                .withEntity(ENTITY_DOCUMENT, documentId)
                .withDetails(Map.of("transaction", transactionId.toString(), "replacement", replacementId.toString())));
        return files.open(storageKey);
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
