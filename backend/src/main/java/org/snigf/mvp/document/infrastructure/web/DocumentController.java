// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.application.DocumentService;
import org.snigf.mvp.document.domain.DocumentVersion;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The documents of the caller's own application (MVP-02, E-13, ADR-033). An upload is a
 * multipart part {@code file} with an {@code Idempotency-Key}; its declared type and size are
 * ignored, the bytes decide. A download is always an attachment, with headers that forbid a
 * browser from rendering or guessing it.
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/documents")
class DocumentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final DocumentService documents;
    private final DocumentProperties settings;

    DocumentController(DocumentService documents, DocumentProperties settings) {
        this.documents = documents;
        this.settings = settings;
    }

    record VersionResponse(UUID id, int versionNo, String status, String originalName, long sizeBytes, String type,
                           String sha256, Instant uploadedAt) {
    }

    record CategoryResponse(String code, boolean required, String labelFr, String labelEn, List<VersionResponse> versions) {
    }

    /**
     * @param missingRequired required categories without a stored version
     * @param maxBytes        the largest file accepted, for the interface to say so beforehand
     */
    record DocumentsResponse(boolean acceptsDocuments, List<String> openCategories, long maxBytes,
                             List<String> allowedTypes, List<String> missingRequired, List<CategoryResponse> categories) {
    }

    @GetMapping
    @PreAuthorize("@access.canOwn(authentication, 'application.read')")
    DocumentsResponse list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return response(documents.list(user, id), settings.maxBytes());
    }

    /** The listing as both the applicant's and the staff endpoints answer it. */
    static DocumentsResponse response(DocumentService.Listing listing, long maxBytes) {
        List<CategoryResponse> categories = listing.application().categories().stream()
                .map(c -> new CategoryResponse(c.code(), c.required(), c.labelFr(), c.labelEn(),
                        listing.versions().stream().filter(v -> v.category().equals(c.code()))
                                .map(DocumentController::version).toList()))
                .toList();
        return new DocumentsResponse(listing.application().acceptsDocuments(), listing.application().openCategories(), maxBytes,
                List.of("application/pdf", "image/jpeg", "image/png"), DocumentService.missingRequired(listing),
                categories);
    }

    @PostMapping(path = "/{category}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canOwn(authentication, 'application.create')")
    ResponseEntity<DocumentService.Uploaded> upload(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable UUID id, @PathVariable String category,
                                                    @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                                    @RequestPart("file") MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            DocumentService.Outcome outcome = documents.upload(user, id, category, file.getOriginalFilename(), content, key);
            return ResponseEntity.created(URI.create("/api/v1/applications/" + id + "/documents/"
                            + outcome.uploaded().id() + "/content"))
                    .header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                    .body(outcome.uploaded());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/{versionId}/content")
    @PreAuthorize("@access.canOwn(authentication, 'application.read')")
    ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                                 @PathVariable UUID versionId) {
        return attachment(documents.download(user, id, versionId));
    }

    /** A stored version as an attachment, with headers that forbid rendering or guessing it. */
    static ResponseEntity<InputStreamResource> attachment(DocumentService.Download download) {
        DocumentVersion v = download.version();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(v.type().mediaType()));
        headers.setContentLength(v.sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(v.originalName(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Repr-Digest", "sha-256=:" + java.util.Base64.getEncoder()
                .encodeToString(java.util.HexFormat.of().parseHex(v.sha256())) + ":");
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }

    private static VersionResponse version(DocumentVersion v) {
        return new VersionResponse(v.id(), v.versionNo(), v.status().name(), v.originalName(), v.sizeBytes(),
                v.type().mediaType(), v.sha256(), v.uploadedAt());
    }
}
