// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.professional.application.ProfessionalService;
import org.snigf.mvp.professional.domain.Professional;
import org.snigf.mvp.shared.security.PublicEndpoint;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Professional accounts (ADR-041 §3). The public request answers 201 whether or not the address was
 * new. The professional reads their own status. Administrators read, verify, approve, suspend and
 * request changes, each with its own permission; the database decides who may approve what.
 */
@RestController
class ProfessionalController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ProfessionalService professionals;
    private final ProfessionalRateLimiter rateLimiter;

    ProfessionalController(ProfessionalService professionals, ProfessionalRateLimiter rateLimiter) {
        this.professionals = professionals;
        this.rateLimiter = rateLimiter;
    }

    record RequestBody_(@Size(max = 254) String email, @Size(max = 120) String displayName, @Size(max = 256) String password,
                        @Size(max = 2) String preferredLanguage, @Size(max = 20) String profession, @Size(max = 200) String chamber,
                        @Size(max = 64) String registrationNumber, @Size(max = 200) String jurisdiction) {

        @Override
        public String toString() {
            return "RequestBody[<redacted>]";
        }
    }

    record VerificationBody(@Size(max = 200) String body, @Size(max = 200) String reference, LocalDate checkedOn, @Size(max = 2000) String note,
                            @Size(max = 50) List<@Size(max = 16) String> offices) {
    }

    record ReasonBody(@Size(max = 2000) String reason) {
    }

    record ChangeBody(@Size(max = 20) String kind, @Size(max = 50) List<@Size(max = 16) String> officesAdd,
                      @Size(max = 50) List<@Size(max = 16) String> officesRemove, @Size(max = 2000) String reason) {
    }

    record Created(UUID id) {
    }

    /** Evidence without its storage key. */
    record EvidenceResponse(UUID id, String sha256, long sizeBytes, String mediaType, String originalName, String uploadedByName,
                            java.time.Instant uploadedAt) {
    }

    record VerificationResponse(UUID id, UUID verifiedBy, String verifiedByName, String body, String reference, LocalDate checkedOn, String note,
                                java.time.Instant verifiedAt, List<EvidenceResponse> documents) {
    }

    record DetailResponse(Professional.Profile profile, List<Professional.Request> requests, VerificationResponse verification,
                          List<Professional.HistoryEntry> history) {
    }

    // --- The professional --------------------------------------------------------------------------------

    @PostMapping("/api/v1/professional-requests")
    @PublicEndpoint("a notary or sworn surveyor requests an account (ADR-041); throttled, and silent about existing addresses")
    ResponseEntity<Void> request(@Valid @RequestBody RequestBody_ body, HttpServletRequest request) {
        rateLimiter.check(request);
        professionals.request(new Professional.NewRequest(body.email(), body.displayName(), body.password(), body.preferredLanguage(),
                body.profession(), body.chamber(), body.registrationNumber(), body.jurisdiction()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/api/v1/professional/me")
    @PreAuthorize("isAuthenticated()")
    ProfessionalService.Own own(@AuthenticationPrincipal AuthenticatedUser user) {
        return professionals.own(user);
    }

    // --- Administrators -----------------------------------------------------------------------------------

    @GetMapping("/api/v1/admin/professionals")
    @PreAuthorize("@access.can(authentication, 'admin.professional.read')")
    List<Professional.Profile> profiles(@AuthenticationPrincipal AuthenticatedUser admin, @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page) {
        return professionals.profiles(admin, status, page);
    }

    @GetMapping("/api/v1/admin/professionals/{id}")
    @PreAuthorize("@access.can(authentication, 'admin.professional.read')")
    DetailResponse detail(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
        ProfessionalService.Detail d = professionals.detail(admin, id);
        VerificationResponse verification = d.verification().map(v -> new VerificationResponse(v.id(), v.verifiedBy(), v.verifiedByName(), v.body(),
                v.reference(), v.checkedOn(), v.note(), v.verifiedAt(), v.documents().stream().map(e -> new EvidenceResponse(e.id(), e.sha256(),
                        e.sizeBytes(), e.mediaType(), e.originalName(), e.uploadedByName(), e.uploadedAt())).toList())).orElse(null);
        return new DetailResponse(d.profile(), d.requests(), verification, d.history());
    }

    @PostMapping("/api/v1/admin/professionals/{id}/verification")
    @PreAuthorize("@access.can(authentication, 'admin.professional.verify')")
    ResponseEntity<Created> verify(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody VerificationBody body) {
        UUID request = professionals.verify(admin, id, new Professional.NewVerification(body.body(), body.reference(), body.checkedOn(), body.note(),
                body.offices()));
        return ResponseEntity.created(URI.create("/api/v1/admin/professional-requests/" + request)).body(new Created(request));
    }

    @PostMapping(path = "/api/v1/admin/professionals/{id}/verification/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.can(authentication, 'admin.professional.verify')")
    ResponseEntity<Created> addEvidence(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key, @RequestPart("file") MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            UUID created = professionals.addEvidence(admin, id, file.getOriginalFilename(), content, key);
            return ResponseEntity.created(URI.create("/api/v1/admin/professionals/" + id + "/verification/documents/" + created + "/content"))
                    .body(new Created(created));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Readable by those who verify or approve only (row-level security); always an attachment, audited. */
    @GetMapping("/api/v1/admin/professionals/{id}/verification/documents/{documentId}/content")
    @PreAuthorize("@access.can(authentication, 'admin.professional.read')")
    ResponseEntity<InputStreamResource> evidence(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                                 @PathVariable UUID documentId) {
        ProfessionalService.EvidenceDownload download = professionals.evidence(admin, id, documentId);
        Professional.Evidence e = download.evidence();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(e.mediaType()));
        headers.setContentLength(e.sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(e.originalName(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Repr-Digest", "sha-256=:" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(e.sha256())) + ":");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }

    @PostMapping("/api/v1/admin/professionals/{id}/suspension")
    @PreAuthorize("@access.can(authentication, 'admin.professional.suspend')")
    ResponseEntity<Void> suspend(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        professionals.suspend(admin, id, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/professionals/{id}/requests")
    @PreAuthorize("@access.can(authentication, 'admin.professional.suspend')")
    ResponseEntity<Created> requestChange(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody ChangeBody body) {
        UUID request = professionals.requestChange(admin, id, new Professional.NewChange(body.kind(), body.officesAdd(), body.officesRemove(),
                body.reason()));
        return ResponseEntity.created(URI.create("/api/v1/admin/professional-requests/" + request)).body(new Created(request));
    }

    @GetMapping("/api/v1/admin/professional-requests")
    @PreAuthorize("@access.can(authentication, 'admin.professional.read')")
    List<Professional.Request> pending(@AuthenticationPrincipal AuthenticatedUser admin, @RequestParam(defaultValue = "0") int page) {
        return professionals.pendingRequests(admin, page);
    }

    @PostMapping("/api/v1/admin/professional-requests/{id}/approval")
    @PreAuthorize("@access.can(authentication, 'admin.professional.approve')")
    Professional.Request approve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
                                 @Valid @RequestBody(required = false) ReasonBody body) {
        return professionals.decide(admin, id, true, body == null ? null : body.reason());
    }

    @PostMapping("/api/v1/admin/professional-requests/{id}/rejection")
    @PreAuthorize("@access.can(authentication, 'admin.professional.approve')")
    Professional.Request reject(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return professionals.decide(admin, id, false, body.reason());
    }
}
