// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.SpecialGrantService;
import org.snigf.mvp.identity.domain.SpecialGrant;
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
 * Special grants of one permission to one person (ADR-025): requested by one administrator,
 * approved or rejected by another, revocable at any time. Limits are an end date, a number of
 * uses, both or neither.
 */
@RestController
@RequestMapping(SecurityConfiguration.API + "/admin/grants")
class GrantController {

    private final SpecialGrantService grants;

    GrantController(SpecialGrantService grants) {
        this.grants = grants;
    }

    record GrantBody(@NotNull UUID userId, @NotNull @Size(max = 64) String permission,
                     @Size(max = 16) String officeCode, @NotNull @Size(max = 500) String reason,
                     Instant validUntil, Integer maxUses) {
    }

    record DecisionBody(@NotNull @Size(max = 500) String reason) {
    }

    @GetMapping
    @PreAuthorize("@access.can(authentication, 'admin.grant.read')")
    List<SpecialGrant> list(@RequestParam(required = false) UUID userId,
                            @RequestParam(required = false) SpecialGrant.Status status) {
        return grants.grants(userId, status);
    }

    @PostMapping
    @PreAuthorize("@access.can(authentication, 'admin.grant.request')")
    ResponseEntity<Map<String, UUID>> request(@AuthenticationPrincipal AuthenticatedUser admin,
                                              @Valid @RequestBody GrantBody body) {
        UUID id = grants.request(admin, body.userId(), body.permission(), body.officeCode(), body.reason(),
                body.validUntil(), body.maxUses());
        return ResponseEntity.created(URI.create(SecurityConfiguration.API + "/admin/grants/" + id))
                .body(Map.of("grantId", id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@access.can(authentication, 'admin.grant.approve')")
    ResponseEntity<Void> approve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                 @Valid @RequestBody DecisionBody body) {
        grants.approve(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@access.can(authentication, 'admin.grant.approve')")
    ResponseEntity<Void> reject(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                @Valid @RequestBody DecisionBody body) {
        grants.reject(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("@access.can(authentication, 'admin.grant.revoke')")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                @Valid @RequestBody DecisionBody body) {
        grants.revoke(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }
}
