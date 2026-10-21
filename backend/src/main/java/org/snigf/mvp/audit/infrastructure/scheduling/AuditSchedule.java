// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.audit.application.AuditProperties;
import org.snigf.mvp.audit.application.AuditTrailService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily checkpoint and full verification (P9), and an optional verification once the
 * application is ready. Times and zone come from {@code SNIGF_AUDIT_*}. Each job acts as a
 * named service, so its own audit events say which job wrote them.
 */
@Component
public class AuditSchedule {

    static final String CHECKPOINT_ACTOR = "audit-checkpointer";
    static final String VERIFIER_ACTOR = "audit-verifier";
    private static final Logger log = LoggerFactory.getLogger(AuditSchedule.class);

    private final AuditTrailService trail;
    private final boolean verifyOnStartup;

    public AuditSchedule(AuditTrailService trail, AuditProperties properties) {
        this.trail = trail;
        this.verifyOnStartup = properties.verifyOnStartup();
    }

    @Scheduled(cron = "${snigf.audit.checkpoint-cron}", zone = "${snigf.audit.schedule-zone}")
    void dailyCheckpoint() {
        SystemActor.run(CHECKPOINT_ACTOR, () -> {
            try {
                if (trail.checkpointDue()) {
                    trail.createCheckpoint();
                } else {
                    log.info("Audit checkpoint skipped: no new events since the last checkpoint");
                }
            } catch (RuntimeException e) {
                // A broken chain is already logged, audited and reported; the next run retries.
                log.error("Scheduled audit checkpoint failed: {}", e.getMessage());
            }
        });
    }

    @Scheduled(cron = "${snigf.audit.verify-cron}", zone = "${snigf.audit.schedule-zone}")
    void dailyVerification() {
        SystemActor.run(VERIFIER_ACTOR, () -> {
            try {
                trail.verify(null, null);
            } catch (RuntimeException e) {
                log.error("Scheduled audit verification failed: {}", e.getMessage());
            }
        });
    }

    /** In the background, so a long chain does not delay readiness. */
    @EventListener(ApplicationReadyEvent.class)
    void verifyWhenReady() {
        if (verifyOnStartup) {
            Thread.ofVirtual().name("audit-startup-verification").start(this::dailyVerification);
        }
    }
}
