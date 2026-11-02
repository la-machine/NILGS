// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.infrastructure.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.snigf.mvp.professional.application.ProfessionalProperties;
import org.snigf.mvp.shared.web.ClientAddress;
import org.springframework.stereotype.Component;

/**
 * Public account requests per client address and hour, like registration ({@code SNIGF_RATE_REGISTER_PER_HOUR}):
 * token buckets in memory for the most recently seen addresses; the address comes from
 * {@link ClientAddress}, so a caller cannot choose its bucket.
 */
@Component
class ProfessionalRateLimiter {

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
    private final int perHour;

    ProfessionalRateLimiter(ProfessionalProperties settings) {
        this.perHour = settings.requestsPerHour();
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
            bucket = buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(Bandwidth.builder().capacity(perHour)
                    .refillIntervally(perHour, Duration.ofHours(1)).build()).build());
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new Limited(Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds()));
        }
    }
}
