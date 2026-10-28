// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.application.PaymentLookup;
import org.snigf.mvp.payment.domain.Fee;
import org.snigf.mvp.payment.domain.PaymentRepository;
import org.snigf.mvp.payment.domain.PaymentRequest;
import org.snigf.mvp.payment.domain.Standing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PaymentLookup} for the completeness review: where the DEMO fee stands, under the actor
 * the application module already named (row-level security shows the requests of the cases they
 * may read). Whether acceptance waits is the database's answer (V18), the same one its guard uses.
 */
@Service
public class PaymentLookupService implements PaymentLookup {

    private final PaymentRepository payments;
    private final Clock clock;

    public PaymentLookupService(PaymentRepository payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentSummary of(UUID applicationId) {
        Optional<Fee> fee = payments.feeOf(applicationId);
        List<PaymentRequest> requests = payments.ofApplication(applicationId);
        Standing standing = Standing.of(fee.isPresent(), requests, clock.instant());
        PaymentRequest latest = requests.isEmpty() ? null : requests.getFirst();
        return new PaymentSummary(standing.name(), latest == null ? null : latest.reference(),
                fee.map(Fee::amount).orElse(null), fee.map(Fee::currency).orElse(null),
                payments.blocksAcceptance(applicationId));
    }
}
