// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.application;

import java.time.ZoneId;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings under {@code snigf.application.*}, from {@code SNIGF_APPLICATION_*} in .env.
 *
 * @param types         where the type catalogue is defined, as a Spring resource
 *                      ({@code SNIGF_APPLICATION_TYPES}, {@code classpath:application-types.json};
 *                      {@code file:/...} for a mounted file)
 * @param referenceZone the time zone whose calendar year goes into references
 *                      ({@code SNIGF_APPLICATION_REFERENCE_ZONE})
 * @param pageSize      applications per page ({@code SNIGF_APPLICATION_PAGE_SIZE})
 * @param maxDrafts     drafts one applicant may hold at once ({@code SNIGF_APPLICATION_MAX_DRAFTS})
 */
@Validated
@ConfigurationProperties(prefix = "snigf.application")
public record ApplicationProperties(@NotBlank String types, @NotNull ZoneId referenceZone,
                                    @Min(5) @Max(200) int pageSize, @Min(1) @Max(100) int maxDrafts) {
}
