// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Reconciliation (ADR-037): what the database decided about a provider report, and what the
 * finance officer reads. Money reported taken without a pending request of the same amount is an
 * exception, never a payment; resolving an exception closes it, never pays the request.
 */
public final class Reconciliation {

    private Reconciliation() {
    }

    public enum Outcome {
        MATCHED, FAILED, AMOUNT_MISMATCH, UNMATCHED_REFERENCE, AFTER_EXPIRY, ALREADY_SETTLED, NO_EFFECT;

        public boolean exception() {
            return this == AMOUNT_MISMATCH || this == UNMATCHED_REFERENCE || this == AFTER_EXPIRY || this == ALREADY_SETTLED;
        }
    }

    public enum Resolution { REFUND_SIMULATED, NO_ACTION }

    /**
     * The database's decision on one report.
     *
     * @param duplicate  the transaction id was already recorded: nothing changed, {@code outcome} is the first one
     * @param expiredNow the request named was pending past its expiry and is now expired
     * @param requestId  the request the reference names; null when none
     */
    public record Applied(UUID recordId, Outcome outcome, boolean duplicate, boolean expiredNow, UUID requestId,
                          String requestReference, UUID applicationId, Long expectedAmount) {
    }

    /** A request as the finance officer reads it. */
    public record FinanceRequest(UUID id, String reference, String applicationReference, long amount, String currency,
                                 PaymentRequest.Status status, Instant createdAt, Instant expiresAt, Instant settledAt,
                                 String transactionId) {
    }

    /** An exception as the finance officer reads it; the resolution fields are null while open. */
    public record FinanceException(UUID id, String transactionId, String reportedReference, long reportedAmount,
                                   String reportedCurrency, Outcome outcome, Instant receivedAt, String requestReference,
                                   Long expectedAmount, String requestStatus, String applicationReference,
                                   Resolution resolution, String resolutionNote, String resolvedByName,
                                   Instant resolvedAt) {
    }

    public record Summary(long pending, long paid, long failed, long expired, long openExceptions) {
    }
}
