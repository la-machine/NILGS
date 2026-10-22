// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.AccountAdministrationService;
import org.snigf.mvp.identity.domain.RoleAssignmentRepository;
import org.snigf.mvp.identity.infrastructure.security.SecurityConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration endpoints (Slice 1 design §5): the account directory, role grants and
 * revocations, and the reset of a lost second factor. Each names its permission; the
 * service enforces separation of duties and audits every change.
 */
@RestController
@RequestMapping(SecurityConfiguration.API + "/admin/users")
class AdminController {

    private final AccountAdministrationService administration;

    AdminController(AccountAdministrationService administration) {
        this.administration = administration;
    }

    record GrantRequest(@NotNull @Size(max = 40) String role, @NotNull @Size(max = 16) String officeCode,
                        @NotNull @Size(max = 500) String reason) {
    }

    record RevokeRequest(@NotNull @Size(max = 500) String reason) {
    }

    record ResetRequest(@NotNull @Size(max = 500) String reason, @NotNull @Size(max = 120) String confirmedBy) {
    }

    @GetMapping
    @PreAuthorize("@access.can(authentication, 'admin.account.list')")
    List<RoleAssignmentRepository.AccountSummary> accounts(@RequestParam(defaultValue = "") String q,
                                                           @RequestParam(defaultValue = "0") int page) {
        return administration.accounts(q, page);
    }

    @PostMapping("/{userId}/roles")
    @PreAuthorize("@access.can(authentication, 'admin.role.assign')")
    ResponseEntity<Map<String, UUID>> grant(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID userId,
                                            @Valid @RequestBody GrantRequest body) {
        // 202: requested, not granted; a second administrator decides (ADR-026).
        UUID id = administration.requestRole(admin, userId, body.role(), body.officeCode(), body.reason());
        return ResponseEntity.accepted()
                .location(URI.create(SecurityConfiguration.API + "/admin/assignment-requests/" + id))
                .body(Map.of("requestId", id));
    }

    @DeleteMapping("/{userId}/roles/{assignmentId}")
    @PreAuthorize("@access.can(authentication, 'admin.role.revoke')")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID userId,
                                @PathVariable UUID assignmentId, @Valid @RequestBody RevokeRequest body) {
        administration.revokeRole(admin, userId, assignmentId, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/mfa-reset")
    @PreAuthorize("@access.can(authentication, 'admin.mfa.reset')")
    ResponseEntity<Void> resetSecondFactor(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID userId,
                                           @Valid @RequestBody ResetRequest body) {
        administration.resetSecondFactor(admin, userId, body.reason(), body.confirmedBy());
        return ResponseEntity.noContent().build();
    }
}
