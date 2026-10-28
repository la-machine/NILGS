// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.conflict.domain.ConflictAlertRepository;
import org.snigf.mvp.conflict.domain.ConflictPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the database's detection policy equal to the settings: applied at startup, and recorded
 * in the audit trail whenever it changes, with the previous and new thresholds.
 */
@Service
public class ConflictPolicyService {

    private final ConflictAlertRepository alerts;
    private final AuditRecorder audit;

    public ConflictPolicyService(ConflictAlertRepository alerts, AuditRecorder audit) {
        this.alerts = alerts;
        this.audit = audit;
    }

    /** Applies the configured thresholds; true when they changed the policy. */
    @Transactional
    public boolean apply(ConflictProperties settings) {
        ConflictPolicy wanted = new ConflictPolicy(settings.minOverlapM2(), settings.duplicateShare());
        List<String> problems = wanted.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe conflict detection settings, refusing to start: " + problems);
        }
        ConflictPolicy current = alerts.policy();
        if (wanted.sameAs(current)) {
            return false;
        }
        alerts.applyPolicy(wanted);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("minOverlapM2", wanted.minOverlapM2());
        details.put("duplicateShare", wanted.duplicateShare());
        details.put("previousMinOverlapM2", current.minOverlapM2());
        details.put("previousDuplicateShare", current.duplicateShare());
        audit.record(AuditEvent.of(AuditAction.CONFLICT_POLICY_CHANGED, AuditOutcome.SUCCESS).withDetails(details));
        return true;
    }
}
