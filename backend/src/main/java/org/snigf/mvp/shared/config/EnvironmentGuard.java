// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application in an unsafe configuration (E-23).
 *
 * <p>Runs while the context is created, before the web server accepts requests.
 * Rules:
 * <ul>
 *   <li>exactly one environment profile (dev, test, staging, prod) is active, and it
 *       matches {@code snigf.environment};</li>
 *   <li>the application connects as {@code snigf_app}, never as the schema owner or a
 *       superuser (ADR-009);</li>
 *   <li>in prod: demo mode is off and the database password is not blank or a known
 *       placeholder.</li>
 * </ul>
 * The check for demo seed rows in a production database is added with the seed data (E-03).
 */
@Component
public class EnvironmentGuard {

    static final String APP_DB_ROLE = "snigf_app";
    private static final Set<String> PLACEHOLDER_SECRETS = Set.of("", "postgres", "password", "changeme", "secret", "snigf");

    public EnvironmentGuard(SnigfProperties properties, Environment environment) {
        List<String> violations = check(
                properties,
                Arrays.asList(environment.getActiveProfiles()),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration, refusing to start:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static List<String> check(SnigfProperties properties, List<String> activeProfiles,
                              String dbUsername, String dbPassword) {
        List<String> violations = new ArrayList<>();

        Set<String> environmentProfiles = Arrays.stream(DeploymentEnvironment.values())
                .map(DeploymentEnvironment::profile)
                .collect(Collectors.toSet());
        List<String> active = activeProfiles.stream().filter(environmentProfiles::contains).toList();
        if (active.size() != 1) {
            violations.add("exactly one of the profiles " + environmentProfiles + " must be active, found " + active);
        } else if (!active.getFirst().equals(properties.environment().profile())) {
            violations.add("profile '" + active.getFirst() + "' does not match snigf.environment="
                    + properties.environment());
        }

        if (!APP_DB_ROLE.equals(dbUsername)) {
            violations.add("the application must connect as " + APP_DB_ROLE + ", not '" + dbUsername + "'");
        }

        if (properties.environment() == DeploymentEnvironment.PROD) {
            if (properties.demoMode()) {
                violations.add("snigf.demo-mode must be false in prod");
            }
            if (dbPassword == null || PLACEHOLDER_SECRETS.contains(dbPassword.strip().toLowerCase(Locale.ROOT))) {
                violations.add("the database password is missing or a placeholder");
            }
        }
        return violations;
    }
}
