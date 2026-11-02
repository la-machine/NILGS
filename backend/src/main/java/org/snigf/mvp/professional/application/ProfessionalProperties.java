// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.application;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Professional-account settings under {@code snigf.professional.*}. They reuse existing values rather
 * than adding new ones: the administration requests' time to live and the registration throttle.
 *
 * @param requestTtl        how long a request waits for its second administrator ({@code SNIGF_ADMIN_REQUEST_TTL})
 * @param requestsPerHour   public account requests per client address and hour ({@code SNIGF_RATE_REGISTER_PER_HOUR})
 * @param maxTrackedClients client addresses whose limit is remembered ({@code SNIGF_RATE_MAX_TRACKED_CLIENTS})
 */
@Validated
@ConfigurationProperties(prefix = "snigf.professional")
public record ProfessionalProperties(@NotNull Duration requestTtl, @Min(1) int requestsPerHour, @Min(100) int maxTrackedClients) {
}
