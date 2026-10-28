// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.infrastructure.web;

import java.util.UUID;

import org.snigf.mvp.application.application.CaseService;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A case for reviewers and decision authorities (ADR-035), whose {@code application.read} is at
 * scope ASSIGNED: row-level security shows them only the cases assigned to them and those of
 * their office waiting to be claimed at their stage; anything else is "not found".
 */
@RestController
@RequestMapping("/api/v1/assigned-cases")
class AssignedCaseController {

    private final CaseService cases;

    AssignedCaseController(CaseService cases) {
        this.cases = cases;
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.canAssigned(authentication, 'application.read')")
    CaseController.CaseResponse read(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return CaseController.response(cases.read(staff, id));
    }
}
