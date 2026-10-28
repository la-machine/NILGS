// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.reporting.domain.StageTargets;
import org.snigf.mvp.reporting.domain.Workload;
import org.snigf.mvp.reporting.domain.WorkloadRepository;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workload report of an office (MVP-06, E-16, ADR-036), for holders of
 * {@code report.workload} there: counts per state, cases under way with the time in their state
 * and an overdue flag against the DEMO targets, the load per person, and the stage durations of
 * the period. Read only. The actor is named to the database first: its report functions (V17)
 * check the permission again and answer nothing for an office the actor may not read.
 * The report carries no form, document, applicant name or review: only references, states,
 * times and the names of the staff holding cases.
 */
@Service
public class WorkloadService {

    private final WorkloadRepository workload;
    private final DatabaseActor actor;
    private final ReportingProperties settings;
    private final Clock clock;

    public WorkloadService(WorkloadRepository workload, DatabaseActor actor, ReportingProperties settings, Clock clock) {
        this.workload = workload;
        this.actor = actor;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Workload.Summary summary(AuthenticatedUser supervisor, String officeCode) {
        actor.bind(supervisor.userId());
        StageTargets targets = settings.targets();
        Instant asOf = clock.instant();
        Instant from = asOf.minus(settings.durationWindow());
        return new Workload.Summary(officeCode, asOf, from, targets, workload.counts(officeCode),
                workload.openByState(officeCode, targets), workload.assignees(officeCode, targets),
                workload.durations(officeCode, targets, from));
    }

    /**
     * Cases under way, longest waiting first.
     *
     * @param state one of the working states, or null for all of them
     */
    @Transactional(readOnly = true)
    public List<Workload.OpenCase> cases(AuthenticatedUser supervisor, String officeCode, String state, boolean overdueOnly,
                                         int page) {
        actor.bind(supervisor.userId());
        if (page < 0 || page > 10_000) {
            throw new ReportRefused(ReportRefused.Kind.INVALID, "PAGE_OUT_OF_RANGE");
        }
        if (state != null && !StageTargets.WORKING_STATES.contains(state)) {
            throw new ReportRefused(ReportRefused.Kind.INVALID, "STATE_NOT_LISTED");
        }
        return workload.openCases(officeCode, settings.targets(), state, overdueOnly, settings.pageSize(),
                page * settings.pageSize());
    }
}
