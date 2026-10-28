// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.application;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Detection thresholds under {@code snigf.conflict.*}, from {@code SNIGF_CONFLICT_*} in .env
 * (MVP-04). Applied to the database's policy at startup (ConflictPolicySync).
 *
 * @param minOverlapM2   smallest intersection counted as an overlap, m2 on the spheroid
 *                       ({@code SNIGF_CONFLICT_MIN_OVERLAP_M2}, 1.00)
 * @param duplicateShare share of each parcel an overlap must cover to be a possible duplicate
 *                       ({@code SNIGF_CONFLICT_DUPLICATE_SHARE}, 0.95)
 */
@Validated
@ConfigurationProperties(prefix = "snigf.conflict")
public record ConflictProperties(
        @NotNull @DecimalMin("0.01") @DecimalMax("1000") BigDecimal minOverlapM2,
        @NotNull @DecimalMin("0.5") @DecimalMax("1") BigDecimal duplicateShare) {
}
