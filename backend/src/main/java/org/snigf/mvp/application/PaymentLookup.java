// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.UUID;

/**
 * Where an application stands with its DEMO fee (ADR-037), for the completeness review. The
 * application module publishes this contract and the payment module implements it, so
 * applications never depend on payments. Answered under the actor already named.
 */
public interface PaymentLookup {

    /** Call inside a transaction. */
    PaymentSummary of(UUID applicationId);

    /**
     * @param standing         {@code NOT_REQUIRED} (no fee applies), {@code DUE}, {@code PENDING} or {@code PAID}
     * @param reference        the latest payment request's reference; null when none
     * @param amount           the fee owed; null when none applies
     * @param blocksAcceptance the gate is on and nothing is paid: completeness cannot be accepted
     */
    record PaymentSummary(String standing, String reference, Long amount, String currency, boolean blocksAcceptance) {
    }
}
