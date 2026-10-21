// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.shared.web.ClientAddress;
import org.springframework.stereotype.Component;

/**
 * Throttling P6 per client address (token buckets, Bucket4j): login attempts per minute
 * and registrations per hour. The address comes from {@link ClientAddress}, so a browser
 * cannot pick its own bucket. Requests whose address is unknown share one bucket.
 *
 * <p>Buckets live in memory, for the least recently seen {@code maxTrackedClients}
 * addresses; one application instance is assumed (the demonstration). Several instances
 * would need a shared store (Bucket4j has PostgreSQL and Redis back ends).
 */
@Component
public class AuthRateLimiter {

    /** A rejected attempt and when the client may try again. */
    public static class Limited extends RuntimeException {
        private final long retryAfterSeconds;

        Limited(long retryAfterSeconds) {
            super("rate limit reached");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    enum Kind { LOGIN, REGISTER }

    private final Map<String, Bucket> buckets;
    private final IdentityProperties.RateLimit limits;

    public AuthRateLimiter(IdentityProperties properties) {
        this.limits = properties.rateLimit();
        int max = limits.maxTrackedClients();
        this.buckets = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > max;
            }
        };
    }

    /** @throws Limited when the client has used up its attempts */
    void check(Kind kind, HttpServletRequest request) {
        String key = kind + "|" + ClientAddress.of(request).orElse("unknown");
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.computeIfAbsent(key, k -> newBucket(kind));
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new Limited(Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds()));
        }
    }

    private Bucket newBucket(Kind kind) {
        Bandwidth limit = kind == Kind.LOGIN
                ? Bandwidth.builder().capacity(limits.loginPerMinute())
                        .refillIntervally(limits.loginPerMinute(), Duration.ofMinutes(1)).build()
                : Bandwidth.builder().capacity(limits.registerPerHour())
                        .refillIntervally(limits.registerPerHour(), Duration.ofHours(1)).build();
        return Bucket.builder().addLimit(limit).build();
    }
}
