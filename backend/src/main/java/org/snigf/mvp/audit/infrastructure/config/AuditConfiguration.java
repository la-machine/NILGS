// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.config;

import java.util.ArrayList;
import java.util.List;

import org.snigf.mvp.audit.application.AuditProperties;
import org.snigf.mvp.shared.config.DeploymentEnvironment;
import org.snigf.mvp.shared.config.SnigfProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the audit schedules and refuses unsafe audit settings at startup: outside tests
 * the daily checkpoint and verification must both run, and outside development the
 * checkpoint directory must be an absolute path.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AuditConfiguration {

    public AuditConfiguration(AuditProperties audit, SnigfProperties snigf) {
        List<String> violations = check(audit, snigf.environment());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe audit configuration, refusing to start:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static List<String> check(AuditProperties audit, DeploymentEnvironment environment) {
        List<String> violations = new ArrayList<>();
        if (environment == DeploymentEnvironment.TEST) {
            return violations;
        }
        if (AuditProperties.DISABLED.equals(audit.checkpointCron().strip())) {
            violations.add("the daily audit checkpoint must be scheduled (SNIGF_AUDIT_CHECKPOINT_CRON)");
        }
        if (AuditProperties.DISABLED.equals(audit.verifyCron().strip())) {
            violations.add("the daily audit verification must be scheduled (SNIGF_AUDIT_VERIFY_CRON)");
        }
        if (environment != DeploymentEnvironment.DEV && !audit.checkpointDirectory().isAbsolute()) {
            violations.add("the audit checkpoint directory must be an absolute path (SNIGF_AUDIT_CHECKPOINT_DIR)");
        }
        return violations;
    }
}
