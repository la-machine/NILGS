// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.payment.application.ReconciliationService;
import org.snigf.mvp.payment.domain.PaymentRequest;
import org.snigf.mvp.payment.domain.Reconciliation;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The finance officer's reconciliation (ADR-037): {@code payment.reconcile}, held everywhere,
 * checked here and again by the database's functions (V18). Resolving an exception needs an
 * {@code Idempotency-Key} and never pays a request.
 */
@RestController
@RequestMapping("/api/v1/payments")
class ReconciliationController {

    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    record SummaryResponse(boolean demo, long pending, long paid, long failed, long expired, long openExceptions) {
    }

    record RequestResponse(UUID id, String reference, String applicationReference, long amount, String currency,
                           String status, Instant createdAt, Instant expiresAt, Instant settledAt, String transactionId) {
    }

    /**
     * @param expectedAmount null when no request matches the reference
     * @param resolution     null while open
     */
    record ExceptionResponse(UUID id, String transactionId, String reportedReference, long reportedAmount,
                             String reportedCurrency, String outcome, Instant receivedAt, String requestReference,
                             Long expectedAmount, String requestStatus, String applicationReference, String resolution,
                             String resolutionNote, String resolvedByName, Instant resolvedAt) {
    }

    record ResolutionBody(@NotNull Reconciliation.Resolution resolution, @NotNull @Size(max = 2000) String note) {
    }

    record ResolvedResponse(UUID id) {
    }

    @GetMapping("/summary")
    @PreAuthorize("@access.can(authentication, 'payment.reconcile')")
    SummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser officer) {
        Reconciliation.Summary s = reconciliation.summary(officer);
        return new SummaryResponse(true, s.pending(), s.paid(), s.failed(), s.expired(), s.openExceptions());
    }

    @GetMapping
    @PreAuthorize("@access.can(authentication, 'payment.reconcile')")
    List<RequestResponse> requests(@AuthenticationPrincipal AuthenticatedUser officer,
                                   @RequestParam(required = false) PaymentRequest.Status status,
                                   @RequestParam(defaultValue = "0") int page) {
        return reconciliation.requests(officer, status, page).stream()
                .map(r -> new RequestResponse(r.id(), r.reference(), r.applicationReference(), r.amount(), r.currency(),
                        r.status().name(), r.createdAt(), r.expiresAt(), r.settledAt(), r.transactionId()))
                .toList();
    }

    @GetMapping("/exceptions")
    @PreAuthorize("@access.can(authentication, 'payment.reconcile')")
    List<ExceptionResponse> exceptions(@AuthenticationPrincipal AuthenticatedUser officer,
                                       @RequestParam(required = false) Boolean resolved,
                                       @RequestParam(defaultValue = "0") int page) {
        return reconciliation.exceptions(officer, resolved, page).stream()
                .map(e -> new ExceptionResponse(e.id(), e.transactionId(), e.reportedReference(), e.reportedAmount(),
                        e.reportedCurrency(), e.outcome().name(), e.receivedAt(), e.requestReference(), e.expectedAmount(),
                        e.requestStatus(), e.applicationReference(), e.resolution() == null ? null : e.resolution().name(),
                        e.resolutionNote(), e.resolvedByName(), e.resolvedAt()))
                .toList();
    }

    @PostMapping("/exceptions/{id}/resolution")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can(authentication, 'payment.reconcile')")
    ResolvedResponse resolve(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id,
                             @RequestHeader(name = PaymentController.IDEMPOTENCY_KEY, required = false) String key,
                             @Valid @RequestBody ResolutionBody body) {
        return new ResolvedResponse(reconciliation.resolve(officer, id, body.resolution(), body.note(), key));
    }
}
