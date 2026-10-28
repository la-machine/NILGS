// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.payment.domain.Fee;
import org.snigf.mvp.payment.domain.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the DEMO fee schedule and the payment gate equal to their configuration (ADR-037). A fee
 * that changed closes the version in force and opens a new one from now; a fee no longer
 * configured is closed. Versions are never edited (V18). Each change is audited; an invalid
 * catalogue stops the start with every reason.
 */
@Service
public class FeeCatalogue {

    private final PaymentRepository payments;
    private final AuditRecorder audit;

    public FeeCatalogue(PaymentRepository payments, AuditRecorder audit) {
        this.payments = payments;
        this.audit = audit;
    }

    /** Applies the fees and the gate; returns the number of fee versions written or closed. */
    @Transactional
    public int apply(List<Fee.Definition> definitions, boolean gate) {
        List<String> problems = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (Fee.Definition d : definitions) {
            problems.addAll(d.problems());
            if (!codes.add(d.typeCode())) {
                problems.add("fee " + d.typeCode() + ": defined twice");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid fee catalogue: " + String.join("; ", problems));
        }
        int written = 0;
        for (Fee.Definition d : definitions) {
            Optional<Fee> current = payments.feeInForce(d.typeCode());
            if (current.isPresent() && current.get().sameAs(d)) {
                continue;
            }
            current.ifPresent(f -> payments.closeFee(f.id()));
            payments.addFee(d);
            record(d.typeCode(), d.amount(), d.currency(), current);
            written++;
        }
        for (String code : payments.feeTypeCodes()) {
            if (!codes.contains(code)) {
                Fee closed = payments.feeInForce(code).orElseThrow();
                payments.closeFee(closed.id());
                record(code, 0, closed.currency(), Optional.of(closed));
                written++;
            }
        }
        if (payments.applyGate(gate)) {
            audit.record(AuditEvent.of(AuditAction.PAYMENT_POLICY_CHANGED, AuditOutcome.SUCCESS)
                    .withEntityRef("payment_policy").withDetails(Map.of("requiredBeforeAcceptance", gate)));
        }
        return written;
    }

    /** {@code amount} 0: no fee in force any more. */
    private void record(String typeCode, long amount, String currency, Optional<Fee> previous) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("type", typeCode);
        facts.put("amount", amount);
        facts.put("currency", currency);
        facts.put("previousAmount", previous.map(Fee::amount).orElse(0L));
        facts.put("demo", true);
        audit.record(AuditEvent.of(AuditAction.PAYMENT_FEE_CHANGED, AuditOutcome.SUCCESS)
                .withEntityRef(typeCode).withDetails(facts));
    }
}
