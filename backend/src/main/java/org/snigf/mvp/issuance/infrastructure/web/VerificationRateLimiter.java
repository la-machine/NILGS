// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.infrastructure.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.snigf.mvp.issuance.application.IssuanceProperties;
import org.snigf.mvp.shared.web.ClientAddress;
import org.springframework.stereotype.Component;

/**
 * Public verification attempts per client address and minute ({@code SNIGF_RATE_VERIFY_PER_MINUTE}),
 * like the login throttle: token buckets in memory for the most recently seen addresses; the
 * address comes from {@link ClientAddress}, so a caller cannot choose its bucket.
 */
@Component
class VerificationRateLimiter {

    static class Limited extends RuntimeException {
        private final long retryAfterSeconds;

        Limited(long retryAfterSeconds) {
            super("rate limit reached");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final Map<String, Bucket> buckets;
    private final int perMinute;

    VerificationRateLimiter(IssuanceProperties settings) {
        this.perMinute = settings.verifyPerMinute();
        int max = settings.maxTrackedClients();
        this.buckets = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > max;
            }
        };
    }

    void check(HttpServletRequest request) {
        String key = ClientAddress.of(request).orElse("unknown");
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(Bandwidth.builder().capacity(perMinute)
                    .refillIntervally(perMinute, Duration.ofMinutes(1)).build()).build());
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new Limited(Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds()));
        }
    }
}
