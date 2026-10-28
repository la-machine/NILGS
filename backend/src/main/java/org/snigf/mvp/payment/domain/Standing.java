// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.time.Instant;
import java.util.List;

/** Where an application stands with its DEMO fee, from its requests (newest first). */
public enum Standing {
    /** No fee applies to it. */
    NOT_REQUIRED,
    /** A fee applies and nothing is paid or pending: the applicant may pay. */
    DUE,
    /** A request is pending and not yet expired. */
    PENDING,
    /** A request is paid. */
    PAID;

    public static Standing of(boolean feeApplies, List<PaymentRequest> requests, Instant now) {
        if (requests.stream().anyMatch(r -> r.status() == PaymentRequest.Status.PAID)) {
            return PAID;
        }
        if (!feeApplies) {
            return NOT_REQUIRED;
        }
        return requests.stream().anyMatch(r -> r.statusAt(now) == PaymentRequest.Status.PENDING) ? PENDING : DUE;
    }
}
