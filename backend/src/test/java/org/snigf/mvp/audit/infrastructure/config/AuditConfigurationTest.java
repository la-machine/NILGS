// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.config;

import java.nio.file.Path;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.audit.application.AuditProperties;
import org.snigf.mvp.shared.config.DeploymentEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Outside tests, the daily checkpoint and verification cannot be switched off. */
class AuditConfigurationTest {

    /** Absolute on the machine running the test (a POSIX path is not absolute on Windows). */
    private static final String ABSOLUTE = Path.of("audit-checkpoints").toAbsolutePath().toString();

    private static AuditProperties settings(String dir, String checkpointCron, String verifyCron) {
        return new AuditProperties(Path.of(dir), checkpointCron, verifyCron, ZoneId.of("Africa/Douala"), true, 8192, 1000);
    }

    @Test
    void acceptsScheduledJobsEverywhere() {
        for (DeploymentEnvironment env : DeploymentEnvironment.values()) {
            assertThat(AuditConfiguration.check(settings(ABSOLUTE, "0 0 2 * * *",
                    "0 30 2 * * *"), env)).as(env.name()).isEmpty();
        }
    }

    @Test
    void refusesDisabledJobsOutsideTests() {
        for (DeploymentEnvironment env : new DeploymentEnvironment[] {
                DeploymentEnvironment.DEV, DeploymentEnvironment.STAGING, DeploymentEnvironment.PROD}) {
            assertThat(AuditConfiguration.check(settings(ABSOLUTE, "-", "0 30 2 * * *"), env))
                    .as(env.name()).singleElement().asString().contains("CHECKPOINT_CRON");
            assertThat(AuditConfiguration.check(settings(ABSOLUTE, "0 0 2 * * *", " - "), env))
                    .as(env.name()).singleElement().asString().contains("VERIFY_CRON");
        }
        assertThat(AuditConfiguration.check(settings("x", "-", "-"), DeploymentEnvironment.TEST)).isEmpty();
    }

    @Test
    void allowsARelativeDirectoryOnlyInDevelopment() {
        assertThat(AuditConfiguration.check(settings("../var/audit", "0 0 2 * * *", "0 30 2 * * *"),
                DeploymentEnvironment.DEV)).isEmpty();
        assertThat(AuditConfiguration.check(settings("../var/audit", "0 0 2 * * *", "0 30 2 * * *"),
                DeploymentEnvironment.PROD)).singleElement().asString().contains("absolute");
    }
}
