// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Land-register settings under {@code snigf.register.*}, from {@code SNIGF_REGISTER_*} in .env (ADR-040).
 *
 * @param lockReviewAfter how long after a deposit a holding lock is due for review; it never expires
 *                        on its own ({@code SNIGF_REGISTER_LOCK_REVIEW_AFTER}, default {@code P30D})
 * @param pageSize        entries per page, 5 to 200 ({@code SNIGF_REGISTER_PAGE_SIZE})
 */
@Validated
@ConfigurationProperties(prefix = "snigf.register")
public record RegisterProperties(@NotNull Duration lockReviewAfter, @Min(5) @Max(200) int pageSize) {

    public RegisterProperties {
        if (lockReviewAfter != null && (lockReviewAfter.isNegative() || lockReviewAfter.isZero())) {
            throw new IllegalArgumentException("snigf.register.lock-review-after must be positive");
        }
    }
}
