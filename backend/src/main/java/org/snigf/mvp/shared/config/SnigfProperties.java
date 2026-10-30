// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings under {@code snigf.*}, bound from configuration and validated at
 * startup. Values come from the environment (.env, ADR-014).
 *
 * @param environment which deployment environment this process belongs to; set by
 *                    exactly one of the profiles dev, test, staging, prod
 * @param demoMode    true where synthetic seed data and DEMO labels are expected.
 *                    Defaults to false so a missing setting never enables demo data
 * @param security    security settings
 * @param keys        key custody settings (ADR-018)
 */
@Validated
@ConfigurationProperties(prefix = "snigf")
public record SnigfProperties(
        @NotNull DeploymentEnvironment environment,
        boolean demoMode,
        @NotNull @Valid @DefaultValue Security security,
        @NotNull @Valid @DefaultValue Keys keys) {

    /** @param transport transport-layer settings (ADR-017) */
    public record Security(@NotNull @Valid @DefaultValue Transport transport) {
    }

    /**
     * @param requireClientCertificate  every request must arrive over mutual TLS with a
     *                                  client certificate; only the test profile turns this off
     * @param allowedClientCommonNames  certificate common names allowed to call the API
     *                                  (the reverse proxy's identity)
     * @param clientAddressHeader       header in which the proxy passes the browser's address;
     *                                  believed only from an admitted proxy certificate
     */
    public record Transport(
            @DefaultValue("true") boolean requireClientCertificate,
            @NotNull @DefaultValue List<String> allowedClientCommonNames,
            @Pattern(regexp = "X-[A-Za-z0-9-]{1,60}") String clientAddressHeader) {
    }

    /**
     * Key custody (ADR-018).
     *
     * @param provider where keys live; only the test profile may use {@code EPHEMERAL}
     * @param openbao  connection to OpenBao, required with the {@code OPENBAO} provider
     * @param names    key name per purpose
     */
    public record Keys(
            @NotNull @DefaultValue("OPENBAO") KeyProviderType provider,
            @Valid OpenBao openbao,
            @NotNull @Valid KeyNames names) {
    }

    /**
     * @param url            base URL; must be https (mutual TLS 1.3 with {@code sslBundle})
     * @param sslBundle      SSL bundle holding the application's client certificate and the CA
     * @param authMount      mount path of the certificate login method
     * @param role           login role (and policy) of the application
     * @param transitMount   mount path of the transit engine
     * @param connectTimeout TCP and TLS connection timeout
     * @param requestTimeout timeout of a single request
     */
    public record OpenBao(
            @NotNull URI url,
            @NotBlank String sslBundle,
            @NotBlank String authMount,
            @NotBlank String role,
            @NotBlank String transitMount,
            @NotNull Duration connectTimeout,
            @NotNull Duration requestTimeout) {
    }

    /** One key per purpose; never reuse a key for two purposes. */
    public record KeyNames(
            @NotBlank String auditCheckpoint,
            @NotBlank String recordSigning,
            @NotBlank String dataEncryption,
            @NotBlank String paymentCallback,
            @NotBlank String passwordPepper,
            @NotBlank String blindIndex,
            @NotBlank String documentIssuance) {
    }

    /** Where keys live. */
    public enum KeyProviderType {
        /** OpenBao transit engine; keys never leave it. Every environment except test. */
        OPENBAO,
        /** Random keys generated in memory at startup and lost at shutdown. Automated tests only. */
        EPHEMERAL
    }
}
