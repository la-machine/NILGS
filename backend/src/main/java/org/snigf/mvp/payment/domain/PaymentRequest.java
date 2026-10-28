// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What an applicant owes for an application, and where it stands (V18). The amount is the fee in
 * force when the application was submitted, copied by the database. {@code PENDING} ends in
 * {@code PAID}, {@code FAILED} or {@code EXPIRED}, decided only by the database.
 *
 * @param chargeId the provider's id for the charge; null until the provider accepted it
 */
public record PaymentRequest(UUID id, UUID applicationId, String reference, long amount, String currency,
                             Status status, Scenario scenario, String chargeId, Instant createdAt, Instant expiresAt,
                             Instant settledAt) {

    public enum Status { PENDING, PAID, FAILED, EXPIRED }

    /** The status as of {@code now}: a pending request past its expiry is expired, swept or not. */
    public Status statusAt(Instant now) {
        return status == Status.PENDING && !now.isBefore(expiresAt) ? Status.EXPIRED : status;
    }
}
