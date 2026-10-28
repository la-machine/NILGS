// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.payment.domain.PaymentRepository;
import org.snigf.mvp.payment.domain.ProviderReport;
import org.snigf.mvp.payment.domain.Reconciliation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Callbacks from the payment provider (ADR-037), under the service identity {@value #ACTOR}.
 * <ol>
 *   <li>The provider adapter authenticates the callback: provider identity vouched for by the
 *       proxy, timestamp fresh, signature valid. Anything else is rejected and audited, and
 *       nothing of it is stored.</li>
 *   <li>The database decides the report under a lock (V18): matched, failed, an exception, or a
 *       duplicate of a transaction already recorded, which changes nothing.</li>
 *   <li>Each decision is audited: accepted (with its outcome), duplicate, exception raised, and
 *       expiry when the report found its request past its expiry.</li>
 * </ol>
 */
@Service
public class CallbackService {

    static final String ACTOR = "payment-callback";
    static final String RECORD_ENTITY = "PAYMENT_RECORD";

    private final PaymentProvider provider;
    private final PaymentRepository payments;
    private final PaymentExpiry expiry;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public CallbackService(PaymentProvider provider, PaymentRepository payments, PaymentExpiry expiry,
                           AuditRecorder audit, PlatformTransactionManager transactions) {
        this.provider = provider;
        this.payments = payments;
        this.expiry = expiry;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * @throws PaymentRefused {@code UNAUTHENTIC} (with the rejection reason as its code) or {@code INVALID}
     *                        ({@code CALLBACK_MALFORMED})
     */
    public Reconciliation.Applied receive(PaymentProvider.Callback callback) {
        return SystemActor.call(ACTOR, () -> {
            ProviderReport report;
            try {
                report = provider.authenticate(callback);
            } catch (PaymentProvider.Rejected e) {
                rejected(e.reason());
                throw new PaymentRefused(PaymentRefused.Kind.UNAUTHENTIC, e.reason());
            } catch (PaymentProvider.Malformed e) {
                rejected("CALLBACK_MALFORMED");
                throw new PaymentRefused(PaymentRefused.Kind.INVALID, "CALLBACK_MALFORMED");
            } catch (PaymentProvider.Unavailable e) {
                // Not verified, so not stored: the provider retries later.
                rejected("VERIFICATION_UNAVAILABLE");
                throw new PaymentRefused(PaymentRefused.Kind.UNAVAILABLE, "VERIFICATION_UNAVAILABLE");
            }
            return transaction.execute(status -> decide(report));
        });
    }

    private Reconciliation.Applied decide(ProviderReport report) {
        Reconciliation.Applied applied = payments.apply(report);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("provider", report.provider().name());
        facts.put("transactionId", report.transactionId());
        facts.put("reference", report.reference());
        facts.put("amount", report.amount());
        facts.put("currency", report.currency());
        facts.put("reportedStatus", report.status().name());
        facts.put("keyVersion", report.keyVersion());
        facts.put("outcome", applied.outcome().name());
        if (applied.expectedAmount() != null) {
            facts.put("expectedAmount", applied.expectedAmount());
        }
        if (applied.expiredNow()) {
            expiry.record(applied.requestId(), applied.requestReference(), "CALLBACK_AFTER_EXPIRY");
        }
        if (applied.duplicate()) {
            audit.record(event(AuditAction.PAYMENT_CALLBACK_DUPLICATE, applied, report).withDetails(facts));
            return applied;
        }
        audit.record(event(AuditAction.PAYMENT_CALLBACK_ACCEPTED, applied, report).withDetails(facts));
        if (applied.outcome().exception()) {
            audit.record(event(AuditAction.PAYMENT_EXCEPTION_RAISED, applied, report)
                    .withReason(applied.outcome().name()).withDetails(facts));
        }
        return applied;
    }

    /** On the request when the reference names one, on the provider's record otherwise. */
    private static AuditEvent event(AuditAction action, Reconciliation.Applied applied, ProviderReport report) {
        AuditEvent event = AuditEvent.of(action, AuditOutcome.SUCCESS);
        return applied.requestId() != null
                ? event.withEntity(PaymentService.ENTITY, applied.requestId()).withEntityRef(applied.requestReference())
                : event.withEntity(RECORD_ENTITY, applied.recordId()).withEntityRef(report.transactionId());
    }

    private void rejected(String reason) {
        audit.recordIndependently(AuditEvent.of(AuditAction.PAYMENT_CALLBACK_REJECTED, AuditOutcome.DENIED)
                .withReason(reason).withDetails(Map.of("provider", provider.kind().name(), "reason", reason)));
    }
}
