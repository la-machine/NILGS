// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.application;

import java.nio.file.Path;
import java.time.ZoneId;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Audit settings under {@code snigf.audit.*}, from {@code SNIGF_AUDIT_*} in .env.
 *
 * @param checkpointDirectory where signed checkpoint files are written; a volume of the
 *                            app container only, never mounted into the database
 * @param checkpointCron      when the daily checkpoint runs (Spring cron, six fields);
 *                            {@value #DISABLED} disables it, allowed in tests only
 * @param verifyCron          when the daily full verification runs; same rules
 * @param scheduleZone        time zone of both schedules
 * @param verifyOnStartup     run a full verification in the background once the
 *                            application is ready
 * @param maxDetailsBytes     largest accepted {@code details} JSON, in bytes
 * @param verifyFetchSize     rows fetched per round trip while verifying
 */
@Validated
@ConfigurationProperties(prefix = "snigf.audit")
public record AuditProperties(
        @NotNull Path checkpointDirectory,
        @NotBlank String checkpointCron,
        @NotBlank String verifyCron,
        @NotNull ZoneId scheduleZone,
        boolean verifyOnStartup,
        @Min(256) @Max(65536) int maxDetailsBytes,
        @Min(10) @Max(100000) int verifyFetchSize) {

    /** Spring's value for a disabled cron trigger. */
    public static final String DISABLED = "-";
}
