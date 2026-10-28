// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.application.ApplicationDirectory;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.payment.domain.Fee;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.payment.domain.PaymentReference;
import org.snigf.mvp.payment.domain.PaymentRepository;
import org.snigf.mvp.payment.domain.PaymentRequest;
import org.snigf.mvp.payment.domain.Scenario;
import org.snigf.mvp.payment.domain.Standing;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The applicant's DEMO payment (MVP-08, E-17, ADR-037). Nothing here moves money: the only
 * provider is the simulator.
 * <ul>
 *   <li>Only the applicant of a submitted application in progress pays, once a fee applies
 *       (endpoint, this service: 404 otherwise, row-level security and the database's guard).</li>
 *   <li>Paying needs an {@code Idempotency-Key}: a retry returns the same payment's current state.</li>
 *   <li>The request is committed before the provider is called, never during: if the provider
 *       cannot be reached, it stays pending without a charge, and paying again restarts it.</li>
 * </ul>
 * Every payment opened and every refusal is audited.
 */
@Service
public class PaymentService {

    static final String ENTITY = "PAYMENT_REQUEST";
    static final String PAY_ENDPOINT = "POST /api/v1/applications/{id}/payments";
    private static final Set<String> IN_PROGRESS = Set.of("SUBMITTED", "COMPLETENESS_REVIEW", "INFO_REQUESTED",
            "UNDER_REVIEW", "PENDING_DECISION");

    private final PaymentRepository payments;
    private final ApplicationDirectory applications;
    private final PaymentProvider provider;
    private final PaymentExpiry expiry;
    private final DatabaseActor actor;
    private final IdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final PaymentProperties settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    public PaymentService(PaymentRepository payments, ApplicationDirectory applications, PaymentProvider provider,
                          PaymentExpiry expiry, DatabaseActor actor, IdempotencyStore idempotency, AuditRecorder audit,
                          PaymentProperties settings, Clock clock, PlatformTransactionManager transactions) {
        this.payments = payments;
        this.applications = applications;
        this.provider = provider;
        this.expiry = expiry;
        this.actor = actor;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * What the applicant owes for an application and where it stands.
     *
     * @param fee                      the fee that applies (in force at submission); empty when none
     * @param requiredBeforeAcceptance acceptance waits for payment (the gate)
     * @param current                  the latest request; empty when none
     */
    public record View(UUID applicationId, Optional<Fee> fee, boolean requiredBeforeAcceptance, Standing standing,
                       Optional<PaymentRequest> current, List<PaymentRequest> history) {
    }

    /** The answer stored for an idempotent retry. */
    record Started(UUID paymentId) {
    }

    @Transactional(readOnly = true)
    public View view(AuthenticatedUser applicant, UUID applicationId) {
        actor.bind(applicant.userId());
        owned(applicant, applicationId);
        return current(applicationId);
    }

    /**
     * Opens a payment and asks the provider to charge it.
     *
     * @throws PaymentRefused {@code UNAVAILABLE} when the provider cannot be reached (the request stays pending)
     */
    public View pay(AuthenticatedUser applicant, UUID applicationId, Scenario scenario, String idempotencyKey) {
        UUID paymentId = transaction.execute(status -> open(applicant, applicationId, scenario, idempotencyKey));
        PaymentRequest request = transaction.execute(status -> {
            actor.bind(applicant.userId());
            return payments.find(paymentId).orElseThrow();
        });
        if (request.chargeId() == null && request.statusAt(clock.instant()) == PaymentRequest.Status.PENDING) {
            start(applicant, request);
        }
        return transaction.execute(status -> {
            actor.bind(applicant.userId());
            return current(applicationId);
        });
    }

    /** First transaction: the request, its audit record and the idempotency record commit together. */
    private UUID open(AuthenticatedUser applicant, UUID applicationId, Scenario scenario, String key) {
        actor.bind(applicant.userId());
        if (!IdempotencyStore.validKey(key)) {
            throw refused(applicationId, "IDEMPOTENCY_KEY_REQUIRED", PaymentRefused.Kind.INVALID);
        }
        if (scenario == null) {
            throw refused(applicationId, "SCENARIO_REQUIRED", PaymentRefused.Kind.INVALID);
        }
        String hash = IdempotencyStore.sha256(applicationId + ":" + scenario.name());
        Optional<IdempotencyStore.Stored> earlier = idempotency.find(applicant.userId(), key);
        if (earlier.isPresent()) {
            if (!earlier.get().requestSha256().equals(hash)) {
                throw refused(applicationId, "IDEMPOTENCY_KEY_REUSED", PaymentRefused.Kind.INVALID);
            }
            return json.readValue(earlier.get().body(), Started.class).paymentId();
        }
        ApplicationDirectory.OwnApplication application = owned(applicant, applicationId);
        if (!IN_PROGRESS.contains(application.state())) {
            throw refused(applicationId, "APPLICATION_NOT_IN_PROGRESS", PaymentRefused.Kind.CONFLICT);
        }
        if (payments.feeOf(applicationId).isEmpty()) {
            throw refused(applicationId, "NO_FEE_APPLIES", PaymentRefused.Kind.CONFLICT);
        }
        List<PaymentRequest> requests = payments.ofApplication(applicationId);
        UUID paymentId = null;
        for (PaymentRequest r : requests) {
            PaymentRequest.Status now = r.statusAt(clock.instant());
            if (r.status() == PaymentRequest.Status.PAID) {
                throw refused(applicationId, "ALREADY_PAID", PaymentRefused.Kind.CONFLICT);
            }
            if (now == PaymentRequest.Status.PENDING) {
                if (r.chargeId() != null) {
                    throw refused(applicationId, "PAYMENT_PENDING", PaymentRefused.Kind.CONFLICT);
                }
                paymentId = r.id();   // the provider never took it: start it again
            }
            if (r.status() == PaymentRequest.Status.PENDING && now == PaymentRequest.Status.EXPIRED) {
                expiry.expireDue();   // past its expiry but not yet swept: free the application for a new one
            }
        }
        if (paymentId == null) {
            int year = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();
            PaymentReference reference = PaymentReference.issue(year, payments.nextSequence(year));
            try {
                paymentId = payments.open(applicationId, reference.value(), scenario, applicant.userId(),
                        settings.pendingTtl(), provider.kind());
            } catch (DataAccessException e) {
                // The guard (V18) disagreed, or a concurrent request opened one first.
                throw refused(applicationId, "23505".equals(sqlState(e)) ? "PAYMENT_PENDING" : "PAYMENT_REFUSED",
                        PaymentRefused.Kind.CONFLICT);
            }
            PaymentRequest opened = payments.find(paymentId).orElseThrow();
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("application", application.reference());
            facts.put("amount", opened.amount());
            facts.put("currency", opened.currency());
            facts.put("scenario", scenario.name());
            facts.put("provider", provider.kind().name());
            facts.put("expiresAt", opened.expiresAt().toString());
            facts.put("demo", true);
            audit.record(AuditEvent.of(AuditAction.PAYMENT_REQUESTED, AuditOutcome.SUCCESS)
                    .withEntity(ENTITY, paymentId).withEntityRef(reference.value()).withDetails(facts));
        }
        try {
            idempotency.save(applicant.userId(), key, PAY_ENDPOINT, hash, 201, json.writeValueAsString(new Started(paymentId)));
        } catch (IdempotencyStore.InProgress e) {
            throw new PaymentRefused(PaymentRefused.Kind.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
        }
        return paymentId;
    }

    /** Outside any transaction: the provider is called, then its charge id stored. */
    private void start(AuthenticatedUser applicant, PaymentRequest request) {
        String chargeId;
        try {
            chargeId = provider.start(new PaymentProvider.ChargeOrder(request.reference(), request.amount(),
                    request.currency(), request.scenario()));
        } catch (PaymentProvider.Unavailable e) {
            audit.recordIndependently(AuditEvent.of(AuditAction.PAYMENT_PROVIDER_UNAVAILABLE, AuditOutcome.FAILED)
                    .withEntity(ENTITY, request.id()).withEntityRef(request.reference())
                    .withReason("PROVIDER_UNAVAILABLE").withDetails(Map.of("provider", provider.kind().name())));
            throw new PaymentRefused(PaymentRefused.Kind.UNAVAILABLE, "PROVIDER_UNAVAILABLE");
        }
        transaction.executeWithoutResult(status -> {
            actor.bind(applicant.userId());
            payments.recordCharge(request.id(), chargeId);
        });
    }

    private View current(UUID applicationId) {
        Optional<Fee> fee = payments.feeOf(applicationId);
        List<PaymentRequest> requests = payments.ofApplication(applicationId);
        return new View(applicationId, fee, settings.requiredBeforeAcceptance(),
                Standing.of(fee.isPresent(), requests, clock.instant()), requests.stream().findFirst(), requests);
    }

    /** The actor's own application; anything else is "not found", and the attempt is recorded. */
    private ApplicationDirectory.OwnApplication owned(AuthenticatedUser applicant, UUID applicationId) {
        return applications.own(applicant.userId(), applicationId)
                .orElseThrow(() -> refused(applicationId, "APPLICATION_NOT_FOUND", PaymentRefused.Kind.NOT_FOUND));
    }

    private PaymentRefused refused(UUID applicationId, String code, PaymentRefused.Kind kind) {
        audit.recordIndependently(AuditEvent.of(AuditAction.PAYMENT_REFUSED,
                        kind == PaymentRefused.Kind.NOT_FOUND ? AuditOutcome.DENIED : AuditOutcome.FAILED)
                .withEntity("APPLICATION", applicationId).withReason(code).withDetails(Map.of("code", code)));
        return new PaymentRefused(kind, code);
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }
}
