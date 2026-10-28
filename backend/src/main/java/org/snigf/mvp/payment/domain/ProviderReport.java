// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * What a provider reports about a charge, once its callback is authenticated. It is a claim to
 * reconcile, never a payment by itself: the database matches it by reference and amount (V18).
 *
 * @param keyVersion the version of the callback key that signed it
 */
public record ProviderReport(PaymentProvider.Kind provider, String transactionId, String reference, long amount,
                             String currency, Status status, Instant occurredAt, int keyVersion) {

    public enum Status { SUCCEEDED, FAILED }

    public static final Pattern TRANSACTION_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public ProviderReport {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        if (transactionId == null || !TRANSACTION_ID.matcher(transactionId).matches()) {
            throw new IllegalArgumentException("transaction id malformed");
        }
        if (reference == null || reference.isEmpty() || reference.length() > 64) {
            throw new IllegalArgumentException("reference malformed");
        }
        if (amount < 0 || amount > 1_000_000_000_000L) {
            throw new IllegalArgumentException("amount out of range");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency malformed");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("key version must be >= 1");
        }
    }
}
