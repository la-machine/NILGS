// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Payment settings under {@code snigf.payment.*}, from {@code SNIGF_PAYMENT_*} in .env (ADR-037).
 *
 * @param fees                      where the DEMO fee catalogue is defined, as a Spring resource
 *                                  ({@code SNIGF_PAYMENT_FEES})
 * @param requiredBeforeAcceptance  the gate: completeness is not accepted until the fee is paid
 *                                  ({@code SNIGF_PAYMENT_REQUIRED_BEFORE_ACCEPTANCE})
 * @param pendingTtl                how long a payment may stay pending before it expires, 1 minute to
 *                                  7 days ({@code SNIGF_PAYMENT_PENDING_TTL})
 * @param provider                  the provider; only {@code DEMO_SIMULATOR} is approved
 *                                  ({@code SNIGF_PAYMENT_PROVIDER})
 * @param callbackMaxSkew           how far a callback's timestamp may be from now, 10 seconds to 15
 *                                  minutes ({@code SNIGF_PAYMENT_CALLBACK_MAX_SKEW})
 * @param providerClientNames       the provider certificate names the proxy may vouch for
 *                                  ({@code SNIGF_PAYMENT_PROVIDER_CLIENT_CNS})
 * @param providerIdentityHeader    the header the proxy writes the provider's certificate subject in
 *                                  ({@code SNIGF_PROXY_PROVIDER_CLIENT_HEADER})
 * @param expiryCron                when pending payments past their expiry are marked expired
 *                                  ({@code SNIGF_PAYMENT_EXPIRY_CRON})
 * @param pageSize                  rows per page of the finance lists ({@code SNIGF_PAYMENT_PAGE_SIZE})
 * @param simulator                 how the application reaches the DEMO simulator
 */
@Validated
@ConfigurationProperties(prefix = "snigf.payment")
public record PaymentProperties(@NotBlank String fees, boolean requiredBeforeAcceptance, @NotNull Duration pendingTtl,
                                @NotNull PaymentProvider.Kind provider, @NotNull Duration callbackMaxSkew,
                                @NotEmpty List<@NotBlank String> providerClientNames,
                                @NotBlank String providerIdentityHeader, @NotBlank String expiryCron,
                                @Min(5) @Max(200) int pageSize, @NotNull @Valid Simulator simulator) {

    public PaymentProperties {
        if (pendingTtl != null && (pendingTtl.compareTo(Duration.ofMinutes(1)) < 0 || pendingTtl.compareTo(Duration.ofDays(7)) > 0)) {
            throw new IllegalArgumentException("snigf.payment.pending-ttl must be from PT1M to P7D");
        }
        if (callbackMaxSkew != null
                && (callbackMaxSkew.compareTo(Duration.ofSeconds(10)) < 0 || callbackMaxSkew.compareTo(Duration.ofMinutes(15)) > 0)) {
            throw new IllegalArgumentException("snigf.payment.callback-max-skew must be from PT10S to PT15M");
        }
        providerClientNames = providerClientNames == null ? List.of() : List.copyOf(providerClientNames);
    }

    /**
     * @param channel        {@code HTTPS} (mutual TLS to the simulator service), or {@code IN_PROCESS}
     *                       for the test profile only ({@code SNIGF_PAYMENT_SIMULATOR_CHANNEL})
     * @param url            the simulator's base URL ({@code SNIGF_PAYMENT_SIMULATOR_URL})
     * @param sslBundle      the application's provider-client identity and the CA
     * @param connectTimeout {@code SNIGF_PAYMENT_SIMULATOR_CONNECT_TIMEOUT}
     * @param requestTimeout {@code SNIGF_PAYMENT_SIMULATOR_REQUEST_TIMEOUT}
     */
    public record Simulator(@NotNull Channel channel, URI url, String sslBundle, Duration connectTimeout,
                            Duration requestTimeout) {

        public enum Channel { HTTPS, IN_PROCESS }
    }
}
