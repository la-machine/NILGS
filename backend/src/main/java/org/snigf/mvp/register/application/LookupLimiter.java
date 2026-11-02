// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

/**
 * Title lookups per professional account and hour (ADR-041 §6, {@code SNIGF_RATE_LOOKUP_PER_HOUR}): token
 * buckets in memory for the most recently seen accounts. Keyed by the authenticated account, so changing
 * address does not reset it.
 */
@Component
class LookupLimiter {

    private final Map<UUID, Bucket> buckets;
    private final int perHour;

    LookupLimiter(RegisterProperties settings) {
        this.perHour = settings.lookupsPerHour();
        int max = settings.maxTrackedAccounts();
        this.buckets = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Bucket> eldest) {
                return size() > max;
            }
        };
    }

    /** Whether this account may look up one more title now. */
    boolean tryAcquire(UUID account) {
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.computeIfAbsent(account, k -> Bucket.builder().addLimit(Bandwidth.builder().capacity(perHour)
                    .refillIntervally(perHour, Duration.ofHours(1)).build()).build());
        }
        return bucket.tryConsume(1);
    }
}
