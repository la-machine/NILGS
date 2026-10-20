// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings under {@code snigf.*}.
 *
 * @param environment which deployment environment this process belongs to; set by
 *                    exactly one of the profiles dev, test, staging, prod
 * @param demoMode    true where synthetic seed data and DEMO labels are expected.
 *                    Defaults to false so a missing setting never enables demo data
 */
@Validated
@ConfigurationProperties(prefix = "snigf")
public record SnigfProperties(@NotNull DeploymentEnvironment environment, boolean demoMode) {
}
