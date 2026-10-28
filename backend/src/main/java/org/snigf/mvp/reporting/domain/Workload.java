// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An office's workload as a supervisor reads it (MVP-06, E-16): how many applications are in each
 * state, those still under way with the time spent in their state against its target, who holds
 * them, and how long the stays that ended lasted. Every time comes from the transition table
 * (V17), never from a field a person writes.
 */
public final class Workload {

    private Workload() {
    }

    /** Applications of the office in one state. */
    public record StateCount(String state, long cases) {
    }

    /**
     * An application still under way.
     *
     * @param since        when it entered its state
     * @param waited       how long it has been there
     * @param target       the target of its state; null when the state has none
     * @param assigneeName the reviewer or decision authority holding it; null when nobody does
     */
    public record OpenCase(UUID id, String reference, String parcelReference, String state, Instant since,
                           Duration waited, Duration target, boolean overdue, String assigneeName) {
    }

    /** The cases under way in one state, and how many of them are overdue. */
    public record OpenInState(String state, long open, long overdue) {
    }

    /** The cases one person holds at one stage, and how many of them are overdue. */
    public record AssigneeLoad(UUID assigneeId, String assigneeName, String state, long cases, long overdue) {
    }

    /**
     * The stays in one state that ended in the period of the report.
     *
     * @param overTarget how many lasted longer than the target of the state
     */
    public record StageDuration(String state, long stays, Duration average, Duration median, Duration longest,
                                long overTarget) {
    }

    /**
     * The report of one office.
     *
     * @param from the start of the period the durations cover
     */
    public record Summary(String office, Instant asOf, Instant from, StageTargets targets, List<StateCount> counts,
                          List<OpenInState> open, List<AssigneeLoad> assignees, List<StageDuration> durations) {
    }
}
