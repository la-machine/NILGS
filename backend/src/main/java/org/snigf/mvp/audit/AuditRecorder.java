// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

/**
 * Appends events to the hash-chained audit trail (Slice 1 design §7, ADR-007).
 *
 * <p>Called by services, not controllers. The actor, roles, client address and
 * correlation id are taken from the security context and the current request, or from
 * {@link SystemActor} for scheduled work.
 */
public interface AuditRecorder {

    /**
     * Appends the event inside the caller's transaction, which must exist, be writable and
     * run under READ COMMITTED: if the audit insert fails, the business change rolls back,
     * and if the business change rolls back, so does the event.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException without a
     *         suitable transaction
     */
    AuditReceipt record(AuditEvent event);

    /**
     * Appends a {@link AuditOutcome#DENIED} or {@link AuditOutcome#FAILED} event in its own
     * transaction, so the record survives the rollback of the attempt it describes.
     * Successful events must use {@link #record} instead.
     */
    AuditReceipt recordIndependently(AuditEvent event);
}
