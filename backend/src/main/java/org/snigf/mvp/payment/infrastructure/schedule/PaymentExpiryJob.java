// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.payment.application.PaymentExpiry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks pending payments past their expiry as expired ({@code SNIGF_PAYMENT_EXPIRY_CRON}). The
 * database already treats them as expired from {@code expires_at}; this makes it visible and audited.
 */
@Component
class PaymentExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryJob.class);

    private final PaymentExpiry expiry;

    PaymentExpiryJob(PaymentExpiry expiry) {
        this.expiry = expiry;
    }

    @Scheduled(cron = "${snigf.payment.expiry-cron}")
    void run() {
        int expired = expiry.expireDue();
        if (expired > 0) {
            log.info("Payments: {} pending request(s) expired", expired);
        }
    }
}
