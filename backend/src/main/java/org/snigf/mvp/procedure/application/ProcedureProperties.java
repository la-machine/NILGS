// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.application;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Procedure settings under {@code snigf.procedure.*}, from .env (ADR-042).
 *
 * @param objectionPeriod   the objection period a publication opens, one minute to a year; a DEMO value, never a legal
 *                          deadline ({@code SNIGF_PROCEDURE_OBJECTION_PERIOD}: {@code P30D}, {@code PT5M} in development).
 *                          The database bounds it again.
 * @param noticesPerMinute  public notices listings per client address and minute, 1 to 600 ({@code SNIGF_RATE_NOTICES_PER_MINUTE})
 * @param maxTrackedClients addresses counted at once, the least recent forgotten ({@code SNIGF_RATE_MAX_TRACKED_CLIENTS})
 * @param pageSize          entries per page of the officers' lists and of the public notices, 5 to 200
 *                          ({@code SNIGF_PROCEDURE_PAGE_SIZE})
 */
@Validated
@ConfigurationProperties(prefix = "snigf.procedure")
public record ProcedureProperties(@NotNull Duration objectionPeriod, @Min(1) @Max(600) int noticesPerMinute, @Min(100) int maxTrackedClients,
                                  @Min(5) @Max(200) int pageSize) {

    static final Duration SHORTEST = Duration.ofMinutes(1);
    static final Duration LONGEST = Duration.ofDays(365);

    public ProcedureProperties {
        if (objectionPeriod != null && (objectionPeriod.compareTo(SHORTEST) < 0 || objectionPeriod.compareTo(LONGEST) > 0)) {
            throw new IllegalArgumentException("snigf.procedure.objection-period must be between PT1M and P365D");
        }
    }
}
