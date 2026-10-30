// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.issuance.application.IssuanceProperties;
import org.snigf.mvp.issuance.application.IssuanceService;
import org.snigf.mvp.issuance.application.VerificationService;
import org.snigf.mvp.issuance.domain.IssuanceRepository;
import org.snigf.mvp.issuance.domain.IssuedDocument;
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
 * The document officer's work (ADR-039): an issued document and its status (revoke, cancel,
 * re-issue: {@code document.issued.manage}), and the verification incidents
 * ({@code document.incident.review}). Both everywhere; the database checks the same permissions,
 * and that nobody acts on a document issued to themselves.
 */
@RestController
@RequestMapping("/api/v1")
class DocumentOfficerController {

    private final IssuanceService issuance;
    private final VerificationService verification;
    private final IssuanceProperties settings;

    DocumentOfficerController(IssuanceService issuance, VerificationService verification, IssuanceProperties settings) {
        this.issuance = issuance;
        this.verification = verification;
        this.settings = settings;
    }

    record ReasonBody(@NotNull @Size(max = 2000) String reason) {
    }

    record ResolutionBody(long expectedVersion, @NotNull IssuanceRepository.Incident.Resolution resolution,
                          @NotNull @Size(max = 4000) String note) {
    }

    /** An issued document as the officer sees it: what it states, its status and its replacement. */
    record DocumentResponse(UUID id, String number, String type, String templateVersion, String subjectType,
                            String subjectRef, String officeCode, Instant issuedAt, Map<String, String> printed,
                            int keyVersion, String status, String statusReason, Instant statusChangedAt,
                            String replacement, String verifyUrl, boolean demo) {
    }

    record IncidentResponse(UUID id, String kind, String status, Instant openedAt, String documentNumber, String channel,
                            String claimedNumber, String presentedSha256, String reason, String resolution, String note,
                            String resolvedByName, Instant resolvedAt, long version) {
    }

    @GetMapping("/issued-documents/{number}")
    @PreAuthorize("@access.can(authentication, 'document.issued.manage')")
    DocumentResponse read(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable String number) {
        return response(issuance.read(officer.userId(), number));
    }

    @PostMapping("/issued-documents/{number}/revocation")
    @PreAuthorize("@access.can(authentication, 'document.issued.manage')")
    DocumentResponse revoke(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable String number,
                            @Valid @RequestBody ReasonBody body) {
        return response(issuance.revoke(officer.userId(), number, body.reason()));
    }

    @PostMapping("/issued-documents/{number}/cancellation")
    @PreAuthorize("@access.can(authentication, 'document.issued.manage')")
    DocumentResponse cancel(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable String number,
                            @Valid @RequestBody ReasonBody body) {
        return response(issuance.cancel(officer.userId(), number, body.reason()));
    }

    /** Returns the replacement; the old document now names it. */
    @PostMapping("/issued-documents/{number}/reissue")
    @PreAuthorize("@access.can(authentication, 'document.issued.manage')")
    DocumentResponse reissue(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable String number,
                             @Valid @RequestBody ReasonBody body) {
        return response(issuance.reissue(officer.userId(), number, body.reason()));
    }

    @GetMapping("/verification-incidents")
    @PreAuthorize("@access.can(authentication, 'document.incident.review')")
    List<IncidentResponse> incidents(@AuthenticationPrincipal AuthenticatedUser officer,
                                     @RequestParam(required = false) IssuanceRepository.Incident.Status status,
                                     @RequestParam(defaultValue = "0") int page) {
        return verification.incidents(officer.userId(), status, page).stream().map(DocumentOfficerController::incident).toList();
    }

    @PostMapping("/verification-incidents/{id}/resolution")
    @PreAuthorize("@access.can(authentication, 'document.incident.review')")
    IncidentResponse resolve(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id,
                             @Valid @RequestBody ResolutionBody body) {
        return incident(verification.resolve(officer.userId(), id, body.expectedVersion(), body.resolution(), body.note()));
    }

    private DocumentResponse response(IssuedDocument d) {
        return new DocumentResponse(d.id(), d.number(), d.type().name(), d.templateVersion(), d.subjectType(), d.subjectRef(),
                d.officeCode(), d.issuedAt(), d.printed(), d.keyVersion(), d.status().name(), d.statusReason(),
                d.statusChangedAt(), d.replacement(), settings.verifyUrl(d.number()), true);
    }

    private static IncidentResponse incident(IssuanceRepository.Incident i) {
        return new IncidentResponse(i.id(), i.kind().name(), i.status().name(), i.openedAt(), i.documentNumber(),
                i.channel().name(), i.claimedNumber(), i.presentedSha256Hex(), i.reason(),
                i.resolution() == null ? null : i.resolution().name(), i.note(), i.resolvedByName(), i.resolvedAt(), i.version());
    }
}
