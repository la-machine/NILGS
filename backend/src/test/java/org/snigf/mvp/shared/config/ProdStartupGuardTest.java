// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.snigf.mvp.shared.config.DeploymentEnvironment.DEV;
import static org.snigf.mvp.shared.config.DeploymentEnvironment.PROD;

/** E-23: the application refuses to start in an unsafe configuration. */
class ProdStartupGuardTest {

    private static final String STRONG = "9f2c41d7a0b84e6f";

    @Test
    void acceptsASafeProductionConfiguration() {
        assertThat(EnvironmentGuard.check(new SnigfProperties(PROD, false), List.of("prod"), "snigf_app", STRONG))
                .isEmpty();
    }

    @Test
    void refusesDemoModeInProduction() {
        assertThat(EnvironmentGuard.check(new SnigfProperties(PROD, true), List.of("prod"), "snigf_app", STRONG))
                .singleElement().asString().contains("demo-mode");
    }

    @Test
    void refusesMissingOrPlaceholderPasswordInProduction() {
        for (String password : new String[] {null, "", "  ", "postgres", "ChangeMe"}) {
            assertThat(EnvironmentGuard.check(new SnigfProperties(PROD, false), List.of("prod"), "snigf_app", password))
                    .as("password %s", password)
                    .singleElement().asString().contains("password");
        }
    }

    @Test
    void refusesConnectingAsSchemaOwnerOrSuperuserInAnyEnvironment() {
        assertThat(EnvironmentGuard.check(new SnigfProperties(DEV, true), List.of("dev"), "snigf_migrator", STRONG))
                .singleElement().asString().contains("snigf_app");
        assertThat(EnvironmentGuard.check(new SnigfProperties(PROD, false), List.of("prod"), "postgres", STRONG))
                .singleElement().asString().contains("snigf_app");
    }

    @Test
    void requiresExactlyOneEnvironmentProfileMatchingTheSetting() {
        assertThat(EnvironmentGuard.check(new SnigfProperties(DEV, true), List.of(), "snigf_app", STRONG))
                .singleElement().asString().contains("exactly one");
        assertThat(EnvironmentGuard.check(new SnigfProperties(PROD, false), List.of("dev", "prod"), "snigf_app", STRONG))
                .singleElement().asString().contains("exactly one");
        assertThat(EnvironmentGuard.check(new SnigfProperties(PROD, false), List.of("staging"), "snigf_app", STRONG))
                .singleElement().asString().contains("does not match");
    }

    @Test
    void ignoresUnrelatedProfiles() {
        assertThat(EnvironmentGuard.check(new SnigfProperties(DEV, true), List.of("dev", "debug"), "snigf_app", ""))
                .isEmpty();
    }
}
