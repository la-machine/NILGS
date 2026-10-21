// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.shared.config.EnvironmentGuard.Settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.snigf.mvp.shared.config.DeploymentEnvironment.DEV;
import static org.snigf.mvp.shared.config.DeploymentEnvironment.PROD;
import static org.snigf.mvp.shared.config.DeploymentEnvironment.STAGING;
import static org.snigf.mvp.shared.config.DeploymentEnvironment.TEST;

/** E-23 and ADR-017: the application refuses to start in an unsafe configuration. */
class ProdStartupGuardTest {

    private static final String STRONG = "9f2c41d7a0b84e6f";

    /** A complete, safe configuration for the given environment; tests change one value. */
    private static Settings safe(DeploymentEnvironment env) {
        return new Settings(
                properties(env, env != PROD, true, List.of("snigf-web")),
                List.of(env.profile(), "transport-security"),
                "snigf_app", STRONG, "verify-full",
                null, "snigf-server", "need", "TLSv1.3", "127.0.0.1", "TLSv1.3");
    }

    private static final SnigfProperties.KeyNames NAMES = new SnigfProperties.KeyNames(
            "audit-checkpoint", "record-signing", "data-encryption", "payment-callback", "password-pepper", "blind-index");

    private static SnigfProperties.Keys openBao(String url) {
        return new SnigfProperties.Keys(SnigfProperties.KeyProviderType.OPENBAO,
                new SnigfProperties.OpenBao(URI.create(url), "snigf-openbao", "cert", "snigf-app", "transit",
                        Duration.ofSeconds(5), Duration.ofSeconds(10)),
                NAMES);
    }

    private static SnigfProperties properties(DeploymentEnvironment env, boolean demo, boolean requireCert,
                                              List<String> allowed) {
        return properties(env, demo, requireCert, allowed, openBao("https://openbao:47820"));
    }

    private static SnigfProperties properties(DeploymentEnvironment env, boolean demo, boolean requireCert,
                                              List<String> allowed, SnigfProperties.Keys keys) {
        return new SnigfProperties(env, demo,
                new SnigfProperties.Security(new SnigfProperties.Transport(requireCert, allowed, "X-Snigf-Client-Address")), keys);
    }

    private static Settings with(Settings s, SnigfProperties p) {
        return new Settings(p, s.activeProfiles(), s.dbUsername(), s.dbPassword(), s.dbSslMode(),
                s.serverSslEnabled(), s.serverSslBundle(), s.serverClientAuth(), s.serverTlsProtocols(),
                s.managementAddress(), s.keysTlsProtocols());
    }

    @Test
    void acceptsSafeConfigurationsInEveryEnvironment() {
        for (DeploymentEnvironment env : DeploymentEnvironment.values()) {
            assertThat(EnvironmentGuard.check(safe(env))).as(env.name()).isEmpty();
        }
    }

    @Test
    void refusesDemoModeInProduction() {
        assertThat(EnvironmentGuard.check(with(safe(PROD), properties(PROD, true, true, List.of("snigf-web")))))
                .singleElement().asString().contains("demo-mode");
    }

    @Test
    void refusesMissingOrPlaceholderPasswordInProduction() {
        for (String password : new String[] {null, "", "  ", "postgres", "ChangeMe"}) {
            Settings s = safe(PROD);
            s = new Settings(s.properties(), s.activeProfiles(), s.dbUsername(), password, s.dbSslMode(),
                    s.serverSslEnabled(), s.serverSslBundle(), s.serverClientAuth(), s.serverTlsProtocols(),
                    s.managementAddress(), s.keysTlsProtocols());
            assertThat(EnvironmentGuard.check(s)).as("password %s", password)
                    .singleElement().asString().contains("password");
        }
    }

    @Test
    void refusesAMissingDatabasePasswordOutsideTests() {
        for (DeploymentEnvironment env : List.of(DEV, STAGING)) {
            Settings s = safe(env);
            s = new Settings(s.properties(), s.activeProfiles(), s.dbUsername(), "", s.dbSslMode(),
                    s.serverSslEnabled(), s.serverSslBundle(), s.serverClientAuth(), s.serverTlsProtocols(),
                    s.managementAddress(), s.keysTlsProtocols());
            assertThat(EnvironmentGuard.check(s)).as(env.name()).singleElement().asString().contains("SNIGF_SECRETS_DIR");
        }
    }

    @Test
    void refusesConnectingAsSchemaOwnerOrSuperuser() {
        for (String user : new String[] {"snigf_migrator", "postgres"}) {
            Settings s = safe(DEV);
            s = new Settings(s.properties(), s.activeProfiles(), user, s.dbPassword(), s.dbSslMode(),
                    s.serverSslEnabled(), s.serverSslBundle(), s.serverClientAuth(), s.serverTlsProtocols(),
                    s.managementAddress(), s.keysTlsProtocols());
            assertThat(EnvironmentGuard.check(s)).singleElement().asString().contains("snigf_app");
        }
    }

    @Test
    void requiresExactlyOneEnvironmentProfileMatchingTheSetting() {
        Settings s = safe(PROD);
        assertThat(EnvironmentGuard.check(new Settings(s.properties(), List.of("transport-security"), "snigf_app",
                STRONG, "verify-full", null, "snigf-server", "need", "TLSv1.3", "127.0.0.1", "TLSv1.3")))
                .singleElement().asString().contains("exactly one");
        assertThat(EnvironmentGuard.check(new Settings(s.properties(), List.of("dev", "prod"), "snigf_app",
                STRONG, "verify-full", null, "snigf-server", "need", "TLSv1.3", "127.0.0.1", "TLSv1.3")))
                .singleElement().asString().contains("exactly one");
        assertThat(EnvironmentGuard.check(new Settings(s.properties(), List.of("staging"), "snigf_app",
                STRONG, "verify-full", null, "snigf-server", "need", "TLSv1.3", "127.0.0.1", "TLSv1.3")))
                .singleElement().asString().contains("does not match");
    }

    @Test
    void refusesAnyGapInTransportSecurityOutsideTests() {
        for (DeploymentEnvironment env : List.of(DEV, STAGING, PROD)) {
            SnigfProperties p = safe(env).properties();
            assertThat(EnvironmentGuard.check(new Settings(p, List.of(env.profile()), "snigf_app", STRONG,
                    "verify-full", "false", "snigf-server", "need", "TLSv1.3", "127.0.0.1", "TLSv1.3")))
                    .as("TLS disabled in %s", env).singleElement().asString().contains("server TLS");
            assertThat(EnvironmentGuard.check(new Settings(p, List.of(env.profile()), "snigf_app", STRONG,
                    "verify-full", null, null, "need", "TLSv1.3", "127.0.0.1", "TLSv1.3")))
                    .as("no bundle in %s", env).singleElement().asString().contains("server TLS");
            assertThat(EnvironmentGuard.check(new Settings(p, List.of(env.profile()), "snigf_app", STRONG,
                    "verify-full", null, "snigf-server", "want", "TLSv1.3", "127.0.0.1", "TLSv1.3")))
                    .as("optional client cert in %s", env).singleElement().asString().contains("client-auth");
            assertThat(EnvironmentGuard.check(new Settings(p, List.of(env.profile()), "snigf_app", STRONG,
                    "verify-full", null, "snigf-server", "need", "TLSv1.2,TLSv1.3", "127.0.0.1", "TLSv1.3")))
                    .as("TLS 1.2 allowed in %s", env).singleElement().asString().contains("TLSv1.3");
            assertThat(EnvironmentGuard.check(new Settings(p, List.of(env.profile()), "snigf_app", STRONG,
                    "verify-full", null, "snigf-server", "need", "TLSv1.3", "0.0.0.0", "TLSv1.3")))
                    .as("management exposed in %s", env).singleElement().asString().contains("loopback");
            assertThat(EnvironmentGuard.check(new Settings(p, List.of(env.profile()), "snigf_app", STRONG,
                    "require", null, "snigf-server", "need", "TLSv1.3", "127.0.0.1", "TLSv1.3")))
                    .as("weak sslmode in %s", env).singleElement().asString().contains("verify-full");
            assertThat(EnvironmentGuard.check(with(safe(env), properties(env, env != PROD, true, List.of()))))
                    .as("empty allow-list in %s", env).singleElement().asString().contains("allowed common names");
            assertThat(EnvironmentGuard.check(with(safe(env), properties(env, env != PROD, false, List.of("snigf-web")))))
                    .as("client certificate not required in %s", env).singleElement().asString().contains("client certificate");
        }
    }

    @Test
    void refusesAMissingClientAddressHeaderOutsideTests() {
        for (DeploymentEnvironment env : List.of(DEV, STAGING, PROD)) {
            SnigfProperties p = new SnigfProperties(env, env != PROD, new SnigfProperties.Security(
                    new SnigfProperties.Transport(true, List.of("snigf-web"), null)), openBao("https://openbao:47820"));
            assertThat(EnvironmentGuard.check(with(safe(env), p)))
                    .as(env.name()).singleElement().asString().contains("client-address-header");
        }
    }

    @Test
    void testProfileMayRunWithoutTransportSecurityOrOpenBao() {
        SnigfProperties p = properties(TEST, true, false, List.of(),
                new SnigfProperties.Keys(SnigfProperties.KeyProviderType.EPHEMERAL, null, NAMES));
        assertThat(EnvironmentGuard.check(new Settings(p, List.of("test"), "snigf_app", "", "disable",
                "false", null, null, null, "127.0.0.1", null))).isEmpty();
    }

    @Test
    void refusesInMemoryKeysOutsideTests() {
        SnigfProperties.Keys ephemeral = new SnigfProperties.Keys(SnigfProperties.KeyProviderType.EPHEMERAL, null, NAMES);
        for (DeploymentEnvironment env : List.of(DEV, STAGING, PROD)) {
            assertThat(EnvironmentGuard.check(with(safe(env), properties(env, env != PROD, true, List.of("snigf-web"), ephemeral))))
                    .as("in-memory keys in %s", env).singleElement().asString().contains("OpenBao");
        }
        assertThat(EnvironmentGuard.check(with(safe(TEST), properties(TEST, true, true, List.of("snigf-web"), ephemeral))))
                .isEmpty();
    }

    @Test
    void refusesOpenBaoWithoutHttpsOrTls13() {
        for (DeploymentEnvironment env : DeploymentEnvironment.values()) {
            assertThat(EnvironmentGuard.check(with(safe(env), properties(env, env != PROD, true, List.of("snigf-web"),
                    openBao("http://openbao:47820")))))
                    .as("plain HTTP to OpenBao in %s", env).singleElement().asString().contains("https");
            Settings s = safe(env);
            assertThat(EnvironmentGuard.check(new Settings(s.properties(), s.activeProfiles(), s.dbUsername(),
                    s.dbPassword(), s.dbSslMode(), s.serverSslEnabled(), s.serverSslBundle(), s.serverClientAuth(),
                    s.serverTlsProtocols(), s.managementAddress(), "TLSv1.2,TLSv1.3")))
                    .as("TLS 1.2 to OpenBao in %s", env).singleElement().asString().contains("OpenBao SSL bundle");
        }
        SnigfProperties.Keys missing = new SnigfProperties.Keys(SnigfProperties.KeyProviderType.OPENBAO, null, NAMES);
        assertThat(EnvironmentGuard.check(with(safe(PROD), properties(PROD, false, true, List.of("snigf-web"), missing))))
                .singleElement().asString().contains("snigf.keys.openbao");
    }

    @Test
    void refusesOneKeyForTwoPurposes() {
        SnigfProperties.Keys shared = new SnigfProperties.Keys(SnigfProperties.KeyProviderType.OPENBAO,
                openBao("https://openbao:47820").openbao(),
                new SnigfProperties.KeyNames("k1", "k2", "k3", "k4", "same", "same"));
        assertThat(EnvironmentGuard.check(with(safe(DEV), properties(DEV, true, true, List.of("snigf-web"), shared))))
                .singleElement().asString().contains("own key");
    }
}
