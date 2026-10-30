// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.infrastructure.web;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.snigf.mvp.audit.AuditedPrincipal;
import org.snigf.mvp.issuance.application.IssuanceRefused;
import org.snigf.mvp.issuance.application.VerificationService;
import org.snigf.mvp.issuance.domain.Verification;
import org.snigf.mvp.shared.security.PublicEndpoint;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public verification of a document presented as issued by SNIGF (ADR-039): by number (the QR code,
 * a printed or typed copy) or by file (the PDF). No sign-in is needed; attempts are throttled per
 * address and every one is recorded and audited. The answer names the outcome and, when a record
 * matched, its type, status, issue date, issuing office and replacement: never the holder, the
 * case, the parcel or the content. A number lookup says the record exists, never that a paper is
 * unaltered; only an identical file is "authentic".
 */
@RestController
@RequestMapping("/api/v1/public/verifications")
class PublicVerificationController {

    private final VerificationService verification;
    private final VerificationRateLimiter limiter;

    PublicVerificationController(VerificationService verification, VerificationRateLimiter limiter) {
        this.verification = verification;
        this.limiter = limiter;
    }

    record Office(String code, String nameFr, String nameEn) {
    }

    /** What the public may know of a matched document. */
    record DocumentSummary(String number, String type, String status, Instant issuedAt, Office issuingOffice,
                           String replacement) {
    }

    /**
     * @param document null when nothing matched
     * @param scope    {@code EXACT_FILE} (the bytes were compared) or {@code RECORD_ONLY} (only the number was)
     */
    record VerificationResponse(UUID verification, boolean demo, String channel, String outcome, String reason,
                                String scope, DocumentSummary document) {
    }

    @GetMapping("/{number}")
    @PublicEndpoint("anyone may check a document number presented as issued by SNIGF; throttled, minimal answer (ADR-039)")
    VerificationResponse byNumber(HttpServletRequest request, @AuthenticationPrincipal AuditedPrincipal caller,
                                  @PathVariable String number) {
        limiter.check(request);
        return response(verification.byNumber(number, caller == null ? null : caller.userId()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PublicEndpoint("anyone may check a PDF presented as issued by SNIGF; throttled, minimal answer (ADR-039)")
    VerificationResponse byFile(HttpServletRequest request, @AuthenticationPrincipal AuditedPrincipal caller,
                                @RequestParam(name = "file", required = false) MultipartFile file) throws IOException {
        limiter.check(request);
        if (file == null || file.isEmpty()) {
            throw new IssuanceRefused(IssuanceRefused.Kind.INVALID, "FILE_REQUIRED");
        }
        return response(verification.byFile(file.getBytes(), caller == null ? null : caller.userId()));
    }

    static VerificationResponse response(VerificationService.Answer answer) {
        Verification.Result r = answer.result();
        Verification.Evidence e = r.evidence();
        DocumentSummary summary = e == null || "REGISTRY_INTEGRITY".equals(r.reason()) ? null
                : new DocumentSummary(e.number(), e.type().name(), e.status().name(), e.issuedAt(),
                        e.officeCode() == null ? null : new Office(e.officeCode(), e.officeNameFr(), e.officeNameEn()),
                        e.replacement());
        return new VerificationResponse(answer.verificationId(), true, answer.channel().name(), r.outcome().name(),
                r.reason(), answer.channel() == Verification.Channel.FILE ? "EXACT_FILE" : "RECORD_ONLY", summary);
    }
}
