// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.RoleAdministrationService;
import org.snigf.mvp.identity.domain.RoleChangeRequest;
import org.snigf.mvp.identity.domain.RoleDefinition;
import org.snigf.mvp.identity.infrastructure.security.SecurityConfiguration;
import org.snigf.mvp.shared.security.Permission;
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
 * The permission catalogue, the roles, and the four-eyes requests that create, change or
 * retire a custom role (ADR-025). Reading, requesting and deciding are three permissions, so
 * the person who proposes a role is never alone in making it real.
 */
@RestController
@RequestMapping(SecurityConfiguration.API + "/admin")
class RoleController {

    private final RoleAdministrationService roles;

    RoleController(RoleAdministrationService roles) {
        this.roles = roles;
    }

    record PermissionResponse(String code, String family, boolean delegable, List<String> scopes,
                              String descriptionEn, String descriptionFr) {
    }

    record RoleChangeBody(@NotNull RoleChangeRequest.Kind kind, @NotNull @Size(max = 40) String roleCode,
                          @Size(max = 80) String nameFr, @Size(max = 80) String nameEn,
                          @Size(max = 500) String description,
                          @Size(max = 64) Map<String, Permission.Scope> permissions,
                          @NotNull @Size(max = 500) String reason) {
    }

    record DecisionBody(@NotNull @Size(max = 500) String reason) {
    }

    @GetMapping("/permissions")
    @PreAuthorize("@access.can(authentication, 'admin.role.read')")
    List<PermissionResponse> permissions() {
        return Arrays.stream(Permission.values())
                .map(p -> new PermissionResponse(p.code(), p.family().name(), p.delegable(),
                        p.scopes().stream().map(Enum::name).sorted().toList(), p.descriptionEn(), p.descriptionFr()))
                .toList();
    }

    @GetMapping("/roles")
    @PreAuthorize("@access.can(authentication, 'admin.role.read')")
    List<RoleDefinition> roles() {
        return roles.roles();
    }

    @GetMapping("/role-requests")
    @PreAuthorize("@access.can(authentication, 'admin.role.read')")
    List<RoleChangeRequest> requests(@RequestParam(required = false) RoleChangeRequest.Status status) {
        return roles.requests(status);
    }

    @PostMapping("/role-requests")
    @PreAuthorize("@access.can(authentication, 'admin.role.define')")
    ResponseEntity<Map<String, UUID>> request(@AuthenticationPrincipal AuthenticatedUser admin,
                                              @Valid @RequestBody RoleChangeBody body) {
        UUID id = roles.request(admin, body.kind(), body.roleCode(),
                new RoleChangeRequest.Proposal(body.nameFr(), body.nameEn(), body.description(), body.permissions()),
                body.reason());
        return ResponseEntity.created(URI.create(SecurityConfiguration.API + "/admin/role-requests/" + id))
                .body(Map.of("requestId", id));
    }

    @PostMapping("/role-requests/{id}/approve")
    @PreAuthorize("@access.can(authentication, 'admin.role.approve')")
    ResponseEntity<Void> approve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                 @Valid @RequestBody DecisionBody body) {
        roles.approve(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/role-requests/{id}/reject")
    @PreAuthorize("@access.can(authentication, 'admin.role.approve')")
    ResponseEntity<Void> reject(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                @Valid @RequestBody DecisionBody body) {
        roles.reject(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }
}
