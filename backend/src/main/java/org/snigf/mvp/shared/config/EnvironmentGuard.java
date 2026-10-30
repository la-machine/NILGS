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
 * Refuses to start the application in an unsafe configuration (E-23, ADR-017).
 *
 * <p>Runs while the context is created, before the web server accepts requests.
 * Rules:
 * <ul>
 *   <li>exactly one environment profile (dev, test, staging, prod) is active, and it
 *       matches {@code snigf.environment};</li>
 *   <li>the application connects as {@code snigf_app}, never as the schema owner or a
 *       superuser (ADR-009);</li>
 *   <li>every environment except test runs complete transport security: server TLS 1.3
 *       with a required client certificate, an allow-list of client names and the proxy's
 *       client-address header, management
 *       on loopback only, and {@code sslmode=verify-full} to the database;</li>
 *   <li>every environment except test keeps its keys in OpenBao (ADR-018), reached over
 *       https with an SSL bundle restricted to TLSv1.3; in-memory keys are for tests only;</li>
 *   <li>each key purpose has its own key;</li>
 *   <li>outside test, the database password is present (read from the secrets directory);</li>
 *   <li>in prod: demo mode is off and the database password is not a known placeholder.</li>
 * </ul>
 * The check for demo seed rows in a production database is added with the seed data (E-03).
 */
@Component
public class EnvironmentGuard {

    static final String APP_DB_ROLE = "snigf_app";
    private static final Set<String> PLACEHOLDER_SECRETS = Set.of("", "postgres", "password", "changeme", "secret", "snigf");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "localhost");

    /** The configuration values the guard inspects, gathered so the rules are testable. */
    record Settings(
            SnigfProperties properties,
            List<String> activeProfiles,
            String dbUsername,
            String dbPassword,
            String dbSslMode,
            String serverSslEnabled,
            String serverSslBundle,
            String serverClientAuth,
            String serverTlsProtocols,
            String managementAddress,
            String keysTlsProtocols) {
    }

    public EnvironmentGuard(SnigfProperties properties, Environment environment) {
        List<String> violations = check(new Settings(
                properties,
                Arrays.asList(environment.getActiveProfiles()),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"),
                environment.getProperty("spring.datasource.hikari.data-source-properties.sslmode"),
                environment.getProperty("server.ssl.enabled"),
                environment.getProperty("server.ssl.bundle"),
                environment.getProperty("server.ssl.client-auth"),
                bundleProtocols(environment),
                environment.getProperty("management.server.address"),
                keysProtocols(properties, environment)));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration, refusing to start:\n - "
                    + String.join("\n - ", violations));
        }
    }

    /** With an SSL bundle, only the bundle's own protocol list takes effect. */
    private static String bundleProtocols(Environment environment) {
        String bundle = environment.getProperty("server.ssl.bundle");
        return bundle == null ? null
                : environment.getProperty("spring.ssl.bundle.pem." + bundle + ".options.enabled-protocols");
    }

    private static String keysProtocols(SnigfProperties properties, Environment environment) {
        SnigfProperties.OpenBao openbao = properties.keys().openbao();
        return openbao == null ? null
                : environment.getProperty("spring.ssl.bundle.pem." + openbao.sslBundle() + ".options.enabled-protocols");
    }

    static List<String> check(Settings s) {
        List<String> violations = new ArrayList<>();
        DeploymentEnvironment env = s.properties().environment();

        Set<String> environmentProfiles = Arrays.stream(DeploymentEnvironment.values())
                .map(DeploymentEnvironment::profile)
                .collect(Collectors.toSet());
        List<String> active = s.activeProfiles().stream().filter(environmentProfiles::contains).toList();
        if (active.size() != 1) {
            violations.add("exactly one of the profiles " + environmentProfiles + " must be active, found " + active);
        } else if (!active.getFirst().equals(env.profile())) {
            violations.add("profile '" + active.getFirst() + "' does not match snigf.environment=" + env);
        }

        if (!APP_DB_ROLE.equals(s.dbUsername())) {
            violations.add("the application must connect as " + APP_DB_ROLE + ", not '" + s.dbUsername() + "'");
        }

        checkKeys(s, violations);
        if (env != DeploymentEnvironment.TEST) {
            checkTransport(s, violations);
            if (isBlank(s.dbPassword())) {
                // Spring's config tree silently yields nothing for a path containing "..".
                violations.add("the database password is missing: it is read from the secrets directory"
                        + " (SNIGF_SECRETS_DIR, an absolute path without '..')");
            }
        }

        if (env == DeploymentEnvironment.PROD) {
            if (s.properties().demoMode()) {
                violations.add("snigf.demo-mode must be false in prod");
            }
            if (!isBlank(s.dbPassword()) && PLACEHOLDER_SECRETS.contains(s.dbPassword().strip().toLowerCase(Locale.ROOT))) {
                violations.add("the database password is missing or a placeholder");
            }
        }
        return violations;
    }

    private static void checkTransport(Settings s, List<String> violations) {
        SnigfProperties.Transport transport = s.properties().security().transport();
        if ("false".equalsIgnoreCase(s.serverSslEnabled()) || isBlank(s.serverSslBundle())) {
            violations.add("server TLS must be enabled with an SSL bundle (profile transport-security)");
        }
        if (!"need".equalsIgnoreCase(s.serverClientAuth())) {
            violations.add("server.ssl.client-auth must be 'need' (mutual TLS)");
        }
        if (!"TLSv1.3".equals(s.serverTlsProtocols())) {
            violations.add("the server SSL bundle must enable exactly TLSv1.3 (options.enabled-protocols)");
        }
        if (!transport.requireClientCertificate() || transport.allowedClientCommonNames().isEmpty()
                || transport.allowedClientCommonNames().stream().anyMatch(EnvironmentGuard::isBlank)) {
            violations.add("snigf.security.transport must require a client certificate and list allowed common names");
        }
        if (isBlank(transport.clientAddressHeader())) {
            violations.add("snigf.security.transport.client-address-header must name the proxy's client-address header");
        }
        if (s.managementAddress() == null || !LOOPBACK.contains(s.managementAddress())) {
            violations.add("management.server.address must be loopback, found '" + s.managementAddress() + "'");
        }
        if (!"verify-full".equals(s.dbSslMode())) {
            violations.add("database sslmode must be verify-full, found '" + s.dbSslMode() + "'");
        }
    }

    private static void checkKeys(Settings s, List<String> violations) {
        SnigfProperties.Keys keys = s.properties().keys();
        boolean test = s.properties().environment() == DeploymentEnvironment.TEST;
        if (keys.provider() != SnigfProperties.KeyProviderType.OPENBAO && !test) {
            violations.add("keys must be held by OpenBao (snigf.keys.provider=OPENBAO); in-memory keys are for tests only");
        }
        if (keys.provider() == SnigfProperties.KeyProviderType.OPENBAO) {
            SnigfProperties.OpenBao openbao = keys.openbao();
            if (openbao == null) {
                violations.add("snigf.keys.openbao must be configured with the OPENBAO provider");
            } else {
                if (!"https".equalsIgnoreCase(openbao.url().getScheme())) {
                    violations.add("snigf.keys.openbao.url must use https, found '" + openbao.url() + "'");
                }
                if (!"TLSv1.3".equals(s.keysTlsProtocols())) {
                    violations.add("the OpenBao SSL bundle must enable exactly TLSv1.3 (options.enabled-protocols)");
                }
            }
        }
        if (keys.names() != null) {
            SnigfProperties.KeyNames n = keys.names();
            List<String> names = List.of(n.auditCheckpoint(), n.recordSigning(), n.dataEncryption(),
                    n.paymentCallback(), n.passwordPepper(), n.blindIndex(), n.documentIssuance());
            if (Set.copyOf(names).size() != names.size()) {
                violations.add("each key purpose needs its own key, found " + names);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
