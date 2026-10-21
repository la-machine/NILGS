// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Identity settings under {@code snigf.identity.*}, from {@code SNIGF_PASSWORD_*},
 * {@code SNIGF_LOGIN_*}, {@code SNIGF_RATE_*}, {@code SNIGF_SESSION_*} and
 * {@code SNIGF_CSRF_*} in .env.
 */
@Validated
@ConfigurationProperties(prefix = "snigf.identity")
public record IdentityProperties(
        @NotNull @Valid Password password,
        @NotNull @Valid Lockout lockout,
        @NotNull @Valid Session session,
        @NotNull @Valid Csrf csrf,
        @NotNull @Valid RateLimit rateLimit) {

    /**
     * Password policy P7 and Argon2id cost.
     *
     * @param minLength            minimum length (P7: 12)
     * @param maxLength            maximum length
     * @param argon2MemoryKib      Argon2id memory per hash, in KiB
     * @param argon2Iterations     Argon2id passes
     * @param argon2Parallelism    Argon2id lanes
     * @param maxConcurrentHashes  hashes computed at once; more callers wait
     * @param hashQueueTimeout     how long a caller waits before getting 503
     */
    public record Password(
            @Min(8) @Max(1024) int minLength,
            @Min(8) @Max(1024) int maxLength,
            @Min(1024) @Max(4_194_304) int argon2MemoryKib,
            @Min(1) @Max(64) int argon2Iterations,
            @Min(1) @Max(64) int argon2Parallelism,
            @Min(1) @Max(1024) int maxConcurrentHashes,
            @NotNull Duration hashQueueTimeout) {
    }

    /** Lockout P6: consecutive failures and lock duration. */
    public record Lockout(@Min(1) @Max(100) int maxFailures, @NotNull Duration lockDuration) {
    }

    /**
     * Session limits P5 and cookie. The idle limit is Spring Session's {@code spring.session.timeout}.
     * The cookie is always Secure, HttpOnly and path {@code /}; only these two attributes are settings.
     *
     * @param absoluteTimeout longest life of a session, however active
     * @param cookieName      session cookie name ({@code __Host-} prefix required outside tests)
     * @param cookieSameSite  {@code Strict} or {@code Lax}
     */
    public record Session(@NotNull Duration absoluteTimeout, @NotBlank String cookieName,
                          @NotBlank String cookieSameSite) {
    }

    /**
     * CSRF token transport for the single-page application.
     *
     * @param cookieName cookie the browser reads the token from
     * @param headerName header the browser sends it back in
     */
    public record Csrf(@NotBlank String cookieName, @NotBlank String headerName) {
    }

    /**
     * Throttling P6, per client address.
     *
     * @param loginPerMinute    login attempts per minute
     * @param registerPerHour   registrations per hour
     * @param maxTrackedClients addresses remembered at once (least recently used dropped)
     */
    public record RateLimit(
            @Min(1) int loginPerMinute,
            @Min(1) int registerPerHour,
            @Min(100) int maxTrackedClients) {
    }
}
