// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.payment.domain.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks pending payments past their expiry as expired, under the service identity
 * {@value #ACTOR}, and audits each. Expiry itself is decided by the database from
 * {@code expires_at} (V18): a callback arriving later finds the request expired whether or not
 * this has run; this only makes the state visible and recorded.
 */
@Service
public class PaymentExpiry {

    static final String ACTOR = "payment-expiry";

    private final PaymentRepository payments;
    private final AuditRecorder audit;

    public PaymentExpiry(PaymentRepository payments, AuditRecorder audit) {
        this.payments = payments;
        this.audit = audit;
    }

    /** Returns how many requests expired now. */
    @Transactional
    public int expireDue() {
        return SystemActor.call(ACTOR, () -> {
            List<PaymentRepository.Expired> expired = payments.expireDue();
            for (PaymentRepository.Expired e : expired) {
                record(e.requestId(), e.reference(), "PENDING_TTL_ELAPSED");
            }
            return expired.size();
        });
    }

    /** Records one expiry; {@code reason} says what found it. Call inside a transaction. */
    void record(UUID requestId, String reference, String reason) {
        audit.record(AuditEvent.of(AuditAction.PAYMENT_EXPIRED, AuditOutcome.SUCCESS)
                .withEntity(PaymentService.ENTITY, requestId).withEntityRef(reference).withReason(reason)
                .withDetails(Map.of("reason", reason)));
    }
}
