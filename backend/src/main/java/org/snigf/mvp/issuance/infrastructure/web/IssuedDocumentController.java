// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.infrastructure.web;

import java.time.Instant;
import java.util.UUID;

import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.issuance.application.IssuanceProperties;
import org.snigf.mvp.issuance.application.IssuanceRefused;
import org.snigf.mvp.issuance.application.IssuanceService;
import org.snigf.mvp.issuance.domain.IssuedDocument;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The submission acknowledgment of an application (ADR-039), for its applicant and for staff of its
 * office: its record and its exact PDF, whose fingerprint is checked before it is served. The
 * applicant may ask for it to be issued again after a failure; the same single document results.
 */
@RestController
@RequestMapping("/api/v1")
class IssuedDocumentController {

    private final IssuanceService issuance;
    private final IssuanceProperties settings;

    IssuedDocumentController(IssuanceService issuance, IssuanceProperties settings) {
        this.issuance = issuance;
        this.settings = settings;
    }

    /**
     * @param replacement the number of the document that replaced it; null unless superseded
     */
    record AcknowledgmentResponse(String number, String type, String status, Instant issuedAt, String verifyUrl,
                                  String replacement, boolean demo) {
    }

    @GetMapping("/applications/{id}/acknowledgment")
    @PreAuthorize("@access.canOwn(authentication, 'application.read')")
    AcknowledgmentResponse mine(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id) {
        return response(issuance.acknowledgment(applicant.userId(), id)
                .orElseThrow(() -> new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "NOT_ISSUED")));
    }

    /** Issues it if it was not issued at submission (idempotent: one document per application). */
    @PostMapping("/applications/{id}/acknowledgment")
    @PreAuthorize("@access.canOwn(authentication, 'application.read')")
    AcknowledgmentResponse issue(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id) {
        issuance.issueSubmissionAcknowledgment(applicant.userId(), id);
        return mine(applicant, id);
    }

    @GetMapping("/applications/{id}/acknowledgment/file")
    @PreAuthorize("@access.canOwn(authentication, 'application.read')")
    ResponseEntity<byte[]> myFile(@AuthenticationPrincipal AuthenticatedUser applicant, @PathVariable UUID id) {
        return file(issuance.acknowledgmentFile(applicant.userId(), id));
    }

    @GetMapping("/cases/{id}/acknowledgment")
    @PreAuthorize("@access.canAt(authentication, 'application.read', @caseOffices.of(#id))")
    AcknowledgmentResponse forStaff(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return response(issuance.acknowledgment(staff.userId(), id)
                .orElseThrow(() -> new IssuanceRefused(IssuanceRefused.Kind.NOT_FOUND, "NOT_ISSUED")));
    }

    @GetMapping("/cases/{id}/acknowledgment/file")
    @PreAuthorize("@access.canAt(authentication, 'application.read', @caseOffices.of(#id))")
    ResponseEntity<byte[]> staffFile(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return file(issuance.acknowledgmentFile(staff.userId(), id));
    }

    private AcknowledgmentResponse response(IssuedDocument d) {
        return new AcknowledgmentResponse(d.number(), d.type().name(), d.status().name(), d.issuedAt(),
                settings.verifyUrl(d.number()), d.replacement(), true);
    }

    /** Served as a download, never rendered in place, never cached. */
    private static ResponseEntity<byte[]> file(IssuanceService.Artifact artifact) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("snigf-demo-acknowledgment-" + artifact.document().number() + ".pdf").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(artifact.pdf());
    }
}
