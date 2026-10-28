// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.provider;

import org.snigf.mvp.payment.domain.PaymentProvider;

/**
 * How charges reach the DEMO simulator: over mutual TLS to the {@code payment-sim} service
 * ({@link HttpsSimulatorChannel}), or in process for the test profile only
 * ({@link InProcessSimulatorChannel}).
 */
public interface SimulatorChannel {

    /**
     * Returns the simulator's transaction id for the charge.
     *
     * @throws PaymentProvider.Unavailable when the simulator cannot be reached or refused the order
     */
    String charge(PaymentProvider.ChargeOrder order);
}
