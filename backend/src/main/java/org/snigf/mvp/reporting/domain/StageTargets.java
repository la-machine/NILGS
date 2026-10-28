// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.domain;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DEMO targets of the stages an application waits in (MVP-06: "overdue flag against DEMO stage
 * targets, for example 5 days for completeness"). A case is overdue once it has stayed in its
 * state longer than that state's target; a state without a target (submitted: the automatic check
 * moves it at once) is never overdue. Placeholders until the team validates them.
 */
public record StageTargets(Map<String, Duration> byState) {

    /** The states a case waits in before it ends, in the order of the lifecycle. */
    public static final List<String> WORKING_STATES = List.of(
            "SUBMITTED", "COMPLETENESS_REVIEW", "INFO_REQUESTED", "UNDER_REVIEW", "PENDING_DECISION");

    /** Every state of a submitted application, for the counts (a draft is never counted). */
    public static final List<String> SUBMITTED_STATES = List.of(
            "SUBMITTED", "COMPLETENESS_REVIEW", "INFO_REQUESTED", "UNDER_REVIEW", "PENDING_DECISION",
            "APPROVED", "REFUSED", "WITHDRAWN");

    /** The longest target a setting may give (a target beyond a year is a mistake). */
    public static final Duration LONGEST = Duration.ofDays(366);

    public StageTargets {
        Map<String, Duration> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Duration> e : byState.entrySet()) {
            if (!WORKING_STATES.contains(e.getKey())) {
                throw new IllegalArgumentException("no target for a state a case does not wait in: " + e.getKey());
            }
            Duration d = e.getValue();
            if (d == null || d.isNegative() || d.isZero() || d.compareTo(LONGEST) > 0) {
                throw new IllegalArgumentException("the target of " + e.getKey() + " must be above zero and at most "
                        + LONGEST.toDays() + " days: " + d);
            }
            copy.put(e.getKey(), d);
        }
        byState = Collections.unmodifiableMap(copy);
    }

    /** The target of a state; null when it has none. */
    public Duration of(String state) {
        return byState.get(state);
    }

    /** The targets in whole seconds, as the database's report functions take them. */
    public Map<String, Long> inSeconds() {
        Map<String, Long> seconds = new LinkedHashMap<>();
        byState.forEach((state, d) -> seconds.put(state, d.toSeconds()));
        return seconds;
    }
}
