// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.provider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.snigf.mvp.payment.domain.PaymentProvider;

/**
 * The simulator's charge endpoint, in process: <b>test profile only</b> (refused anywhere else by
 * {@link PaymentProviderConfiguration}). It records each order and answers a transaction id;
 * it sends no callback. Tests sign and post the callbacks themselves, through the same endpoint,
 * signature check and database function as the simulator's.
 */
public class InProcessSimulatorChannel implements SimulatorChannel {

    /** An order received, and the transaction id it was given. */
    public record Order(PaymentProvider.ChargeOrder order, String transactionId) {
    }

    private final List<Order> orders = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;

    @Override
    public String charge(PaymentProvider.ChargeOrder order) {
        if (unavailable) {
            throw new PaymentProvider.Unavailable("the in-process simulator is set unavailable", null);
        }
        String transactionId = "SIMTX-" + UUID.randomUUID();
        orders.add(new Order(order, transactionId));
        return transactionId;
    }

    public List<Order> orders() {
        return List.copyOf(orders);
    }

    /** The last order for a reference. */
    public Order last(String reference) {
        return orders.reversed().stream().filter(o -> o.order().reference().equals(reference)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no order for " + reference));
    }

    /** Makes the next charges fail as if the simulator could not be reached. */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }
}
