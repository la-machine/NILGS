// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.application;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Document settings under {@code snigf.document.*}, from {@code SNIGF_DOCUMENT_*} in .env.
 *
 * @param directory    where files are kept (a volume of the app container only)
 * @param maxBytes     largest file accepted (Baseline: 10 MB DEMO limit; at most 100 MB, V14)
 * @param maxVersions  versions one document may have
 * @param maxPixels    largest image accepted (width x height), against decompression bombs
 * @param orphanGrace  how old an unreferenced file must be before the cleanup removes it
 * @param cleanupCron  when the cleanup runs (Spring cron, six fields)
 */
@Validated
@ConfigurationProperties(prefix = "snigf.document")
public record DocumentProperties(@NotNull Path directory, @Min(1024) @Max(104_857_600) long maxBytes,
                                 @Min(1) @Max(100) int maxVersions, @Min(1_000_000) @Max(500_000_000) long maxPixels,
                                 @NotNull Duration orphanGrace, @NotBlank String cleanupCron) {
}
