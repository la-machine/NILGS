// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.domain;

import java.time.Instant;
import java.util.List;

/**
 * The workload of an office, read through the database's report functions (V17). They answer
 * only for an office where the actor named in the transaction holds {@code report.workload};
 * for any other office every list is empty.
 */
public interface WorkloadRepository {

    /** Applications per state (submitted ones only); states without any are absent. */
    List<Workload.StateCount> counts(String officeCode);

    /** Cases under way and overdue ones, per state; states without any are absent. */
    List<Workload.OpenInState> openByState(String officeCode, StageTargets targets);

    /** Cases held per person and stage, most first. */
    List<Workload.AssigneeLoad> assignees(String officeCode, StageTargets targets);

    /**
     * Cases under way, longest waiting first.
     *
     * @param state       one state, or null for all of them
     * @param overdueOnly only those past their target
     */
    List<Workload.OpenCase> openCases(String officeCode, StageTargets targets, String state, boolean overdueOnly,
                                      int limit, int offset);

    /** The stays that ended since {@code from}, per state; states without any are absent. */
    List<Workload.StageDuration> durations(String officeCode, StageTargets targets, Instant from);
}
