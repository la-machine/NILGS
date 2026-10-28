// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.reporting.application.WorkloadService;
import org.snigf.mvp.reporting.domain.Workload;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workload report of an office (MVP-06, E-16): {@code report.workload} at the office named,
 * checked here and again by the database (V17). Durations are in whole seconds; the interface
 * shows them in days and hours.
 */
@RestController
@RequestMapping("/api/v1/reports/workload")
class WorkloadController {

    private final WorkloadService workload;

    WorkloadController(WorkloadService workload) {
        this.workload = workload;
    }

    record TargetResponse(String state, long seconds) {
    }

    record CountResponse(String state, long cases) {
    }

    record OpenResponse(String state, long open, long overdue) {
    }

    record AssigneeResponse(String name, String state, long cases, long overdue) {
    }

    record DurationResponse(String state, long stays, long averageSeconds, long medianSeconds, long longestSeconds,
                            long overTarget) {
    }

    /**
     * @param from the start of the period the durations cover
     */
    record SummaryResponse(String office, Instant asOf, Instant from, List<TargetResponse> targets,
                           List<CountResponse> counts, List<OpenResponse> open, List<AssigneeResponse> assignees,
                           List<DurationResponse> durations) {
    }

    /**
     * @param targetSeconds null when the state has no target
     * @param assigneeName  null while nobody holds the case
     */
    record CaseResponse(UUID id, String reference, String parcelReference, String state, Instant since,
                        long waitedSeconds, Long targetSeconds, boolean overdue, String assigneeName) {
    }

    @GetMapping
    @PreAuthorize("@access.canAt(authentication, 'report.workload', #office)")
    SummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser supervisor, @RequestParam String office) {
        Workload.Summary s = workload.summary(supervisor, office);
        return new SummaryResponse(s.office(), s.asOf(), s.from(),
                s.targets().inSeconds().entrySet().stream().map(e -> new TargetResponse(e.getKey(), e.getValue())).toList(),
                s.counts().stream().map(c -> new CountResponse(c.state(), c.cases())).toList(),
                s.open().stream().map(o -> new OpenResponse(o.state(), o.open(), o.overdue())).toList(),
                s.assignees().stream().map(a -> new AssigneeResponse(a.assigneeName(), a.state(), a.cases(), a.overdue())).toList(),
                s.durations().stream().map(d -> new DurationResponse(d.state(), d.stays(), d.average().toSeconds(),
                        d.median().toSeconds(), d.longest().toSeconds(), d.overTarget())).toList());
    }

    @GetMapping("/cases")
    @PreAuthorize("@access.canAt(authentication, 'report.workload', #office)")
    List<CaseResponse> cases(@AuthenticationPrincipal AuthenticatedUser supervisor, @RequestParam String office,
                             @RequestParam(required = false) String state,
                             @RequestParam(defaultValue = "false") boolean overdue,
                             @RequestParam(defaultValue = "0") int page) {
        return workload.cases(supervisor, office, state, overdue, page).stream()
                .map(c -> new CaseResponse(c.id(), c.reference(), c.parcelReference(), c.state(), c.since(),
                        c.waited().toSeconds(), c.target() == null ? null : c.target().toSeconds(), c.overdue(),
                        c.assigneeName()))
                .toList();
    }
}
