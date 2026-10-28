// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments as stored (V18). Reads of requests run under the actor already named (row-level
 * security); reports, expiry and the finance reads go through the database's functions.
 */
public interface PaymentRepository {

    // --- Fees and policy -------------------------------------------------------------------------

    /** The version of a type's fee in force now. */
    Optional<Fee> feeInForce(String typeCode);

    /** The fee that applies to an application: the one in force when it was submitted. */
    Optional<Fee> feeOf(UUID applicationId);

    /** The types with a fee in force now. */
    List<String> feeTypeCodes();

    void closeFee(UUID feeId);

    void addFee(Fee.Definition definition);

    /** Writes the gate setting; true when it changed. */
    boolean applyGate(boolean gate);

    // --- Requests --------------------------------------------------------------------------------

    long nextSequence(int year);

    /** Opens a request as the applicant named; the database fills amount, currency and times. */
    UUID open(UUID applicationId, String reference, Scenario scenario, UUID applicant, Duration ttl,
              PaymentProvider.Kind provider);

    Optional<PaymentRequest> find(UUID requestId);

    /** The application's requests, newest first. */
    List<PaymentRequest> ofApplication(UUID applicationId);

    /** Records the provider's charge id once, while pending; false when it was already set. */
    boolean recordCharge(UUID requestId, String chargeId);

    boolean blocksAcceptance(UUID applicationId);

    // --- Reports and expiry ----------------------------------------------------------------------

    Reconciliation.Applied apply(ProviderReport report);

    /** Expires every pending request past its expiry, and returns them. */
    List<Expired> expireDue();

    record Expired(UUID requestId, String reference, UUID applicationId) {
    }

    // --- Finance ---------------------------------------------------------------------------------

    List<Reconciliation.FinanceRequest> financeRequests(PaymentRequest.Status status, int limit, int offset);

    List<Reconciliation.FinanceException> financeExceptions(Boolean resolved, int limit, int offset);

    /** Empty when the actor may not reconcile, or the summary is unknown. */
    Optional<Reconciliation.Summary> financeSummary();

    UUID resolve(UUID recordId, Reconciliation.Resolution resolution, String note);
}
