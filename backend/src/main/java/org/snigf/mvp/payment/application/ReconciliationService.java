// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.payment.domain.PaymentRepository;
import org.snigf.mvp.payment.domain.PaymentRequest;
import org.snigf.mvp.payment.domain.Reconciliation;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The finance officer's reconciliation (ADR-037, {@code payment.reconcile}): payment requests and
 * what the provider reported for them, the exceptions, and their resolution. Reads go through the
 * database's functions, which answer only for a holder of {@code payment.reconcile} and show
 * references and amounts, never a form or a document. A resolution closes an exception (a simulated
 * refund, or nothing to do) and never pays a request; nobody resolves one on their own application.
 */
@Service
public class ReconciliationService {

    static final String RESOLVE_ENDPOINT = "POST /api/v1/payments/exceptions/{id}/resolution";

    private final PaymentRepository payments;
    private final DatabaseActor actor;
    private final IdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final PaymentProperties settings;
    private final JsonMapper json = JsonMapper.builder().build();

    public ReconciliationService(PaymentRepository payments, DatabaseActor actor, IdempotencyStore idempotency,
                                 AuditRecorder audit, PaymentProperties settings) {
        this.payments = payments;
        this.actor = actor;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
    }

    /** The answer stored for an idempotent retry. */
    record Resolved(UUID resolutionId) {
    }

    @Transactional(readOnly = true)
    public List<Reconciliation.FinanceRequest> requests(AuthenticatedUser officer, PaymentRequest.Status status, int page) {
        actor.bind(officer.userId());
        return payments.financeRequests(status, settings.pageSize(), offset(page));
    }

    @Transactional(readOnly = true)
    public List<Reconciliation.FinanceException> exceptions(AuthenticatedUser officer, Boolean resolved, int page) {
        actor.bind(officer.userId());
        return payments.financeExceptions(resolved, settings.pageSize(), offset(page));
    }

    @Transactional(readOnly = true)
    public Reconciliation.Summary summary(AuthenticatedUser officer) {
        actor.bind(officer.userId());
        return payments.financeSummary().orElse(new Reconciliation.Summary(0, 0, 0, 0, 0));
    }

    @Transactional
    public UUID resolve(AuthenticatedUser officer, UUID recordId, Reconciliation.Resolution resolution, String note,
                        String key) {
        actor.bind(officer.userId());
        if (!IdempotencyStore.validKey(key)) {
            throw refused(recordId, "IDEMPOTENCY_KEY_REQUIRED", PaymentRefused.Kind.INVALID);
        }
        if (resolution == null) {
            throw refused(recordId, "RESOLUTION_REQUIRED", PaymentRefused.Kind.INVALID);
        }
        String text = note == null ? "" : note.strip();
        if (text.length() < 10 || text.length() > 1000) {
            throw refused(recordId, "NOTE_REQUIRED", PaymentRefused.Kind.INVALID);
        }
        String hash = IdempotencyStore.sha256(recordId + ":" + resolution.name() + ":" + text);
        Optional<IdempotencyStore.Stored> earlier = idempotency.find(officer.userId(), key);
        if (earlier.isPresent()) {
            if (!earlier.get().requestSha256().equals(hash)) {
                throw refused(recordId, "IDEMPOTENCY_KEY_REUSED", PaymentRefused.Kind.INVALID);
            }
            return json.readValue(earlier.get().body(), Resolved.class).resolutionId();
        }
        UUID id;
        try {
            id = payments.resolve(recordId, resolution, text);
        } catch (DataAccessException e) {
            throw switch (sqlState(e)) {
                case "23505" -> refused(recordId, "ALREADY_RESOLVED", PaymentRefused.Kind.CONFLICT);
                case "23514" -> refused(recordId, "NOT_AN_EXCEPTION", PaymentRefused.Kind.CONFLICT);
                case "23503" -> refused(recordId, "EXCEPTION_NOT_FOUND", PaymentRefused.Kind.NOT_FOUND);
                case "42501" -> refused(recordId, "PAYMENT_RESOLUTION_REFUSED", PaymentRefused.Kind.FORBIDDEN);
                default -> e;
            };
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("resolution", resolution.name());
        facts.put("noteLength", text.length());
        audit.record(AuditEvent.of(AuditAction.PAYMENT_EXCEPTION_RESOLVED, AuditOutcome.SUCCESS)
                .withEntity(CallbackService.RECORD_ENTITY, recordId).withReason(resolution.name()).withDetails(facts));
        try {
            idempotency.save(officer.userId(), key, RESOLVE_ENDPOINT, hash, 201, json.writeValueAsString(new Resolved(id)));
        } catch (IdempotencyStore.InProgress e) {
            throw new PaymentRefused(PaymentRefused.Kind.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
        }
        return id;
    }

    private int offset(int page) {
        if (page < 0 || page > 10_000) {
            throw new PaymentRefused(PaymentRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        return page * settings.pageSize();
    }

    private PaymentRefused refused(UUID recordId, String code, PaymentRefused.Kind kind) {
        audit.recordIndependently(AuditEvent.of(AuditAction.PAYMENT_REFUSED,
                        kind == PaymentRefused.Kind.FORBIDDEN ? AuditOutcome.DENIED : AuditOutcome.FAILED)
                .withEntity(CallbackService.RECORD_ENTITY, recordId).withReason(code).withDetails(Map.of("code", code)));
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
