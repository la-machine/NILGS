// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.util.Map;

/**
 * The payment provider, as application processing uses it (ADR-037). Starting a charge and
 * authenticating a callback are the provider's own protocol; what follows (reconciliation, the
 * gate, the audit) is the same for any provider. Only the DEMO simulator implements it: a future
 * provider needs its adapter, an approved {@link Kind}, a migration and an ADR, and stays disabled
 * until then. No implementation ever moves real money in the MVP.
 */
public interface PaymentProvider {

    /** The providers this build may use. Only the simulator is approved. */
    enum Kind { DEMO_SIMULATOR }

    Kind kind();

    /**
     * Asks the provider to charge; returns the provider's charge id.
     *
     * @throws Unavailable when the provider cannot be reached or refused the order
     */
    String start(ChargeOrder order);

    /**
     * Authenticates a callback and reads its report: identity, freshness and signature first,
     * the body only then.
     *
     * @throws Rejected  when it is not authentic (nothing of it may be stored)
     * @throws Malformed when it is authentic but unreadable
     */
    ProviderReport authenticate(Callback callback);

    /** A charge to start. The scenario is honoured by the simulator only. */
    record ChargeOrder(String reference, long amount, String currency, Scenario scenario) {
    }

    /**
     * A callback as received.
     *
     * @param providerIdentity the provider's verified certificate subject, as the proxy wrote it; null if absent
     * @param headers          header names in lower case
     */
    record Callback(String providerIdentity, Map<String, String> headers, byte[] body) {

        public Callback {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Not authentic; {@link #reason()} is a stable code, never the body. */
    class Rejected extends RuntimeException {
        private final String reason;

        public Rejected(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    class Malformed extends RuntimeException {
        public Malformed(String message) {
            super(message);
        }
    }
}
