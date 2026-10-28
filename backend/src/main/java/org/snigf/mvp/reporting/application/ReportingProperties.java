// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.snigf.mvp.reporting.domain.StageTargets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Reporting settings under {@code snigf.reporting.*}, from {@code SNIGF_REPORTING_*} in .env.
 * The targets are DEMO placeholders (MVP-06) until the team validates them; the application
 * refuses to start with a target of zero, negative, or beyond a year.
 *
 * @param targetCompletenessReview how long a case may wait for the completeness review
 *                                 ({@code SNIGF_REPORTING_TARGET_COMPLETENESS_REVIEW}, Baseline: 5 days)
 * @param targetInfoRequested      how long a case may wait for the applicant's response
 *                                 ({@code SNIGF_REPORTING_TARGET_INFO_REQUESTED})
 * @param targetUnderReview        how long the substantive review may take
 *                                 ({@code SNIGF_REPORTING_TARGET_UNDER_REVIEW})
 * @param targetPendingDecision    how long a case may wait for its decision
 *                                 ({@code SNIGF_REPORTING_TARGET_PENDING_DECISION})
 * @param durationWindow           the period the stage durations cover, back from now
 *                                 ({@code SNIGF_REPORTING_DURATION_WINDOW})
 * @param pageSize                 cases per page of the queue ({@code SNIGF_REPORTING_PAGE_SIZE})
 */
@Validated
@ConfigurationProperties(prefix = "snigf.reporting")
public record ReportingProperties(@NotNull Duration targetCompletenessReview, @NotNull Duration targetInfoRequested,
                                  @NotNull Duration targetUnderReview, @NotNull Duration targetPendingDecision,
                                  @NotNull Duration durationWindow, @Min(5) @Max(200) int pageSize) {

    public ReportingProperties {
        if (durationWindow != null && (durationWindow.isNegative() || durationWindow.isZero()
                || durationWindow.compareTo(Duration.ofDays(3660)) > 0)) {
            throw new IllegalArgumentException("snigf.reporting.duration-window must be above zero and at most ten years");
        }
        if (targetCompletenessReview != null && targetInfoRequested != null && targetUnderReview != null
                && targetPendingDecision != null) {
            targets(targetCompletenessReview, targetInfoRequested, targetUnderReview, targetPendingDecision);
        }
    }

    /** The targets per state; checked at startup. */
    public StageTargets targets() {
        return targets(targetCompletenessReview, targetInfoRequested, targetUnderReview, targetPendingDecision);
    }

    private static StageTargets targets(Duration completeness, Duration info, Duration review, Duration decision) {
        Map<String, Duration> byState = new LinkedHashMap<>();
        byState.put("COMPLETENESS_REVIEW", completeness);
        byState.put("INFO_REQUESTED", info);
        byState.put("UNDER_REVIEW", review);
        byState.put("PENDING_DECISION", decision);
        return new StageTargets(byState);
    }
}
