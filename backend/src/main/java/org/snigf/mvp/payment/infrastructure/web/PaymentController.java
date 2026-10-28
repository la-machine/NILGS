// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.payment.application.PaymentService;
import org.snigf.mvp.payment.domain.PaymentRequest;
import org.snigf.mvp.payment.domain.Scenario;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The applicant's DEMO payment (MVP-08, ADR-037): {@code payment.pay} on their own applications
 * only; the service answers 404 for anyone else's. Paying needs an {@code Idempotency-Key}.
 * Everything says {@code demo: true}: no real money moves.
 */
@RestController
@RequestMapping("/api/v1/applications/{id}")
class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService payments;
    private final Clock clock;

    PaymentController(PaymentService payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    record PayBody(@NotNull Scenario scenario) {
    }

    record FeeResponse(long amount, String currency, String labelFr, String labelEn) {
    }

    /**
     * @param status the status now: a pending request past its expiry reads {@code EXPIRED}
     */
    record RequestResponse(UUID id, String reference, long amount, String currency, String status, String scenario,
                           Instant createdAt, Instant expiresAt, Instant settledAt) {
    }

    /**
     * @param fee                      null when no fee applies
     * @param requiredBeforeAcceptance the completeness review waits for payment
     * @param standing                 {@code NOT_REQUIRED}, {@code DUE}, {@code PENDING} or {@code PAID}
     * @param current                  the latest request; null when none
     */
    record PaymentResponse(UUID applicationId, boolean demo, FeeResponse fee, boolean requiredBeforeAcceptance,
                           String standing, RequestResponse current, List<RequestResponse> history) {
    }

    @GetMapping("/payment")
    @PreAuthorize("@access.canOwn(authentication, 'payment.pay')")
    PaymentResponse read(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id) {
        return response(payments.view(applicant, id));
    }

    @PostMapping("/payments")
    @PreAuthorize("@access.canOwn(authentication, 'payment.pay')")
    PaymentResponse pay(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id,
                        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                        @Valid @RequestBody PayBody body) {
        return response(payments.pay(applicant, id, body.scenario(), key));
    }

    private PaymentResponse response(PaymentService.View view) {
        Instant now = clock.instant();
        return new PaymentResponse(view.applicationId(), true,
                view.fee().map(f -> new FeeResponse(f.amount(), f.currency(), f.labelFr(), f.labelEn())).orElse(null),
                view.requiredBeforeAcceptance(), view.standing().name(),
                view.current().map(r -> request(r, now)).orElse(null),
                view.history().stream().map(r -> request(r, now)).toList());
    }

    private static RequestResponse request(PaymentRequest r, Instant now) {
        return new RequestResponse(r.id(), r.reference(), r.amount(), r.currency(), r.statusAt(now).name(),
                r.scenario().name(), r.createdAt(), r.expiresAt(), r.settledAt());
    }
}
