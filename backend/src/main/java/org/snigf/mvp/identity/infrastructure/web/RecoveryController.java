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
import org.snigf.mvp.identity.application.SecondFactorResetService;
import org.snigf.mvp.identity.domain.MfaResetRequest;
import org.snigf.mvp.identity.infrastructure.security.SecurityConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The supervised reset of a lost second factor (ADR-028): an administrator requests it, the
 * person's supervisor confirms (or rejects) after checking the identity out of band. The
 * confirmation endpoints need {@code identity.reset.confirm} at some office; the service
 * then restricts each request to supervisors of the person's offices.
 */
@RestController
class RecoveryController {

    private final SecondFactorResetService resets;

    RecoveryController(SecondFactorResetService resets) {
        this.resets = resets;
    }

    record ResetBody(@NotNull @Size(max = 500) String reason) {
    }

    record ConfirmBody(@NotNull @Size(max = 200) String identityCheck) {
    }

    @PostMapping(SecurityConfiguration.API + "/admin/users/{userId}/mfa-reset")
    @PreAuthorize("@access.can(authentication, 'admin.mfa.reset')")
    ResponseEntity<Map<String, UUID>> request(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID userId,
                                              @Valid @RequestBody ResetBody body) {
        // 202: requested, not done; the person's supervisor confirms.
        UUID id = resets.request(admin, userId, body.reason());
        return ResponseEntity.accepted()
                .location(URI.create(SecurityConfiguration.API + "/admin/mfa-reset-requests/" + id))
                .body(Map.of("requestId", id));
    }

    @GetMapping(SecurityConfiguration.API + "/admin/mfa-reset-requests")
    @PreAuthorize("@access.can(authentication, 'admin.mfa.reset')")
    List<MfaResetRequest> requests(@RequestParam(required = false) MfaResetRequest.Status status) {
        return resets.requests(status);
    }

    @GetMapping(SecurityConfiguration.API + "/identity/reset-confirmations")
    @PreAuthorize("@access.canSomewhere(authentication, 'identity.reset.confirm')")
    List<MfaResetRequest> toConfirm(@AuthenticationPrincipal AuthenticatedUser supervisor) {
        return resets.toConfirm(supervisor);
    }

    @PostMapping(SecurityConfiguration.API + "/identity/reset-confirmations/{id}/confirm")
    @PreAuthorize("@access.canSomewhere(authentication, 'identity.reset.confirm')")
    ResponseEntity<Void> confirm(@AuthenticationPrincipal AuthenticatedUser supervisor, @PathVariable UUID id,
                                 @Valid @RequestBody ConfirmBody body) {
        resets.confirm(supervisor, id, body.identityCheck());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(SecurityConfiguration.API + "/identity/reset-confirmations/{id}/reject")
    @PreAuthorize("@access.canSomewhere(authentication, 'identity.reset.confirm')")
    ResponseEntity<Void> reject(@AuthenticationPrincipal AuthenticatedUser supervisor, @PathVariable UUID id,
                                @Valid @RequestBody ResetBody body) {
        resets.reject(supervisor, id, body.reason());
        return ResponseEntity.noContent().build();
    }
}
