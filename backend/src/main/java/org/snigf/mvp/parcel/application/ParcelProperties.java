// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.application;

import java.time.ZoneId;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Parcel settings under {@code snigf.parcel.*}, from {@code SNIGF_PARCEL_*} in .env.
 *
 * @param referenceZone the time zone whose calendar year goes into new references
 *                      ({@code SNIGF_PARCEL_REFERENCE_ZONE}, Africa/Douala)
 * @param pageSize      parcels per page in a search ({@code SNIGF_PARCEL_PAGE_SIZE})
 */
@Validated
@ConfigurationProperties(prefix = "snigf.parcel")
public record ParcelProperties(@NotNull ZoneId referenceZone, @Min(5) @Max(200) int pageSize) {
}
