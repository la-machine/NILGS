// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.retention.infrastructure.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.retention.application.RetentionService;
import org.snigf.mvp.retention.domain.Retention;
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
 * Retention and legal holds (ADR-041 D18): read with {@code records.retention.read} (auditors and system administrators),
 * a hold placed with {@code records.hold.place}, its release asked and decided with {@code records.hold.release} by two
 * different people. No endpoint deletes or disposes of anything, and none serves a document.
 */
@RestController
@RequestMapping("/api/v1/records")
class RetentionController {

    private final RetentionService retention;

    RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    record HoldBody(@Size(max = 40) String subjectKind, UUID subjectId, @Size(max = 4000) String reason) {
    }

    record ReasonBody(@Size(max = 4000) String reason) {
    }

    record DecisionBody(@Size(max = 20) String outcome, @Size(max = 4000) String reason) {
    }

    @GetMapping("/retention-classes")
    @PreAuthorize("@access.can(authentication, 'records.retention.read')")
    List<Retention.RetentionClass> classes(@AuthenticationPrincipal AuthenticatedUser reader) {
        return retention.classes(reader);
    }

    /** {@code kind} and {@code id} both, or neither for every hold. */
    @GetMapping("/holds")
    @PreAuthorize("@access.can(authentication, 'records.retention.read')")
    List<Retention.Hold> holds(@AuthenticationPrincipal AuthenticatedUser reader, @RequestParam(required = false) String kind,
                               @RequestParam(required = false) UUID id, @RequestParam(defaultValue = "0") int page) {
        return retention.holds(reader, kind, id, page);
    }

    @GetMapping("/retention")
    @PreAuthorize("@access.can(authentication, 'records.retention.read')")
    Retention.RecordRetention retentionOf(@AuthenticationPrincipal AuthenticatedUser reader, @RequestParam String kind, @RequestParam UUID id) {
        return retention.retentionOf(reader, kind, id);
    }

    @PostMapping("/holds")
    @PreAuthorize("@access.can(authentication, 'records.hold.place')")
    ResponseEntity<Retention.Hold> place(@AuthenticationPrincipal AuthenticatedUser admin, @Valid @RequestBody HoldBody body) {
        Retention.Hold hold = retention.place(admin, body.subjectKind(), body.subjectId(), body.reason());
        return ResponseEntity.created(URI.create("/api/v1/records/holds/" + hold.id())).body(hold);
    }

    @PostMapping("/holds/{id}/release-requests")
    @PreAuthorize("@access.can(authentication, 'records.hold.release')")
    Retention.Hold requestRelease(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return retention.requestRelease(admin, id, body.reason());
    }

    @PostMapping("/holds/{id}/release-requests/{releaseId}/decision")
    @PreAuthorize("@access.can(authentication, 'records.hold.release')")
    Retention.Hold decideRelease(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @PathVariable UUID releaseId,
                                 @Valid @RequestBody DecisionBody body) {
        return retention.decideRelease(admin, id, releaseId, body.outcome(), body.reason());
    }
}
