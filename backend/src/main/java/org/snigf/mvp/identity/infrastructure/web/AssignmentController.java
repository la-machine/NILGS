// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.AccountAdministrationService;
import org.snigf.mvp.identity.domain.RoleAssignmentRequest;
import org.snigf.mvp.identity.infrastructure.security.SecurityConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role assignment requests (ADR-026): one administrator requests a role for a staff member
 * ({@code POST /admin/users/{id}/roles}), another approves or rejects it here. Approval
 * creates the assignment.
 */
@RestController
@RequestMapping(SecurityConfiguration.API + "/admin/assignment-requests")
class AssignmentController {

    private final AccountAdministrationService administration;

    AssignmentController(AccountAdministrationService administration) {
        this.administration = administration;
    }

    record DecisionBody(@NotNull @Size(max = 500) String reason) {
    }

    @GetMapping
    @PreAuthorize("@access.can(authentication, 'admin.assignment.read')")
    List<RoleAssignmentRequest> list(@RequestParam(required = false) RoleAssignmentRequest.Status status) {
        return administration.assignmentRequests(status);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@access.can(authentication, 'admin.assignment.approve')")
    ResponseEntity<Void> approve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                 @Valid @RequestBody DecisionBody body) {
        administration.approveAssignment(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@access.can(authentication, 'admin.assignment.approve')")
    ResponseEntity<Void> reject(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                @Valid @RequestBody DecisionBody body) {
        administration.rejectAssignment(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }
}
