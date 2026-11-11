// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.register.application.ProfessionalDepositService;
import org.snigf.mvp.register.application.RegisterRefused;
import org.snigf.mvp.register.domain.Register;
import org.springframework.core.io.InputStreamResource;
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
 * The professional channel of the DEMO register (ADR-041 §6 and §7), for an approved notary. The lookup
 * answers a title number at an office they serve, and nothing else. A deposit is one multipart request:
 * the title, the kind, the client, and the files with a category each, a deed among them. The rest works
 * only on the professional's own deposits: any other id answers 404, as an unknown one does. Nothing here
 * registers anything, and no conservator endpoint opens to a professional.
 */
@RestController
class ProfessionalDepositController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ProfessionalDepositService deposits;

    ProfessionalDepositController(ProfessionalDepositService deposits) {
        this.deposits = deposits;
    }

    record ReasonBody(@Size(max = 2000) String reason) {
    }

    record Created(UUID id) {
    }

    /** Never the storage key. {@code uploadedBySelf}: false for a file a conservator added. */
    record DocumentResponse(UUID id, UUID transactionId, String category, String sha256, long sizeBytes, String mediaType, String originalName,
                            boolean uploadedBySelf, Instant uploadedAt, UUID replacesId, String replaceReason, UUID replacedById) {
    }

    @GetMapping("/api/v1/professional/titles")
    @PreAuthorize("@access.canSomewhere(authentication, 'register.deposit.submit')")
    Register.TitleLookup lookup(@AuthenticationPrincipal AuthenticatedUser professional, @RequestParam String number) {
        if (number.length() > 64) {
            throw new RegisterRefused(RegisterRefused.Kind.INVALID, "VALIDATION_FAILED");
        }
        return deposits.lookup(professional, number).orElseThrow(() -> RegisterRefused.of("TITLE_NOT_FOUND"));
    }

    /** {@code categories} names each of {@code files}, in order. The title's office must be one the professional serves. */
    @PostMapping(path = "/api/v1/professional/deposits", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'register.deposit.submit', @registerOffices.title(#titleId))")
    ResponseEntity<Created> deposit(@AuthenticationPrincipal AuthenticatedUser professional, @RequestParam UUID titleId, @RequestParam String kind,
                                    @RequestParam String depositorKind, @RequestParam String depositorName, @RequestParam String depositorIdentityKind,
                                    @RequestParam String depositorIdentityNumber, @RequestParam List<String> categories,
                                    @RequestPart("files") List<MultipartFile> files,
                                    @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        if (categories.size() != files.size() || files.size() > 20 || kind.length() > 20 || depositorKind.length() > 20 || depositorName.length() > 200
                || depositorIdentityKind.length() > 40 || depositorIdentityNumber.length() > 64) {
            throw new RegisterRefused(RegisterRefused.Kind.INVALID, "VALIDATION_FAILED");
        }
        List<InputStream> opened = new ArrayList<>();
        try {
            List<ProfessionalDepositService.Upload> uploads = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                InputStream content = files.get(i).getInputStream();
                opened.add(content);
                uploads.add(new ProfessionalDepositService.Upload(categories.get(i), files.get(i).getOriginalFilename(), content));
            }
            UUID id = deposits.deposit(professional, titleId, kind,
                    new Register.NewDepositor(depositorKind, depositorName, depositorIdentityKind, depositorIdentityNumber), uploads, key);
            return ResponseEntity.created(URI.create("/api/v1/professional/deposits/" + id)).body(new Created(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (InputStream content : opened) {
                try {
                    content.close();
                } catch (IOException ignored) {
                    // The upload is spooled by the container, which removes it with the request.
                }
            }
        }
    }

    @GetMapping("/api/v1/professional/deposits")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.read')")
    List<Register.ProfessionalDeposit> myDeposits(@AuthenticationPrincipal AuthenticatedUser professional, @RequestParam(defaultValue = "0") int page) {
        return deposits.deposits(professional, page);
    }

    @GetMapping("/api/v1/professional/deposits/{id}")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.read')")
    Register.ProfessionalDeposit myDeposit(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id) {
        return deposits.deposit(professional, id);
    }

    @PostMapping("/api/v1/professional/deposits/{id}/withdrawal")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.withdraw')")
    Register.ProfessionalDeposit withdraw(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return deposits.withdraw(professional, id, body.reason());
    }

    @GetMapping("/api/v1/professional/deposits/{id}/documents")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.read')")
    List<DocumentResponse> documents(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id) {
        return deposits.documents(professional, id).stream().map(d -> new DocumentResponse(d.id(), d.transactionId(), d.category(), d.sha256(),
                d.sizeBytes(), d.mediaType(), d.originalName(), d.uploadedBySelf(), d.uploadedAt(), d.replacesId(), d.replaceReason(), d.replacedById())).toList();
    }

    @PostMapping(path = "/api/v1/professional/deposits/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.document')")
    ResponseEntity<Created> addDocument(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id, @RequestParam String category,
                                        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key, @RequestPart("file") MultipartFile file) {
        return stored(professional, id, category, file, null, null, key);
    }

    /**
     * Asks for a document to be replaced (ADR-041 D19): the new file is kept with the request, and the original stays
     * the current document until a conservator of the office approves. {@code category}, when given, must be the original's.
     */
    @PostMapping(path = "/api/v1/professional/deposits/{id}/documents/{documentId}/replacement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.document')")
    ResponseEntity<Created> replaceDocument(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id, @PathVariable UUID documentId,
                                            @RequestParam(required = false) String category, @RequestParam String reason,
                                            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key, @RequestPart("file") MultipartFile file) {
        return stored(professional, id, category, file, documentId, reason, key);
    }

    private ResponseEntity<Created> stored(AuthenticatedUser professional, UUID id, String category, MultipartFile file, UUID replaces, String reason,
                                           String key) {
        try (InputStream content = file.getInputStream()) {
            UUID created = deposits.addDocument(professional, id, category, file.getOriginalFilename(), content, replaces, reason, key);
            String location = "/api/v1/professional/deposits/" + id + (replaces == null ? "/documents/" : "/replacements/") + created + "/content";
            return ResponseEntity.created(URI.create(location)).body(new Created(created));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Always an attachment, with the headers of ADR-033 that forbid a browser from rendering or guessing it. */
    @GetMapping("/api/v1/professional/deposits/{id}/documents/{documentId}/content")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.read')")
    ResponseEntity<InputStreamResource> documentContent(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id,
                                                        @PathVariable UUID documentId) {
        ProfessionalDepositService.DocumentDownload download = deposits.download(professional, id, documentId);
        Register.ProfessionalDocument d = download.document();
        return ResponseEntity.ok().headers(DocumentHeaders.attachment(d.mediaType(), d.sizeBytes(), d.originalName(), d.sha256()))
                .body(new InputStreamResource(download.content()));
    }

    // --- Replacement requests on their own deposit (D19); a conservator decides -------------------------------

    /** {@code requestedBySelf}: false for a conservator's request. No conservator is named. */
    record ReplacementResponse(UUID id, UUID documentId, String sha256, long sizeBytes, String mediaType, String originalName, String reason,
                               boolean requestedBySelf, Instant requestedAt, String status, Instant decidedAt, String decisionReason,
                               UUID resultDocumentId) {

        static ReplacementResponse of(Register.ProfessionalReplacement r) {
            return new ReplacementResponse(r.id(), r.documentId(), r.sha256(), r.sizeBytes(), r.mediaType(), r.originalName(), r.reason(),
                    r.requestedBySelf(), r.requestedAt(), r.status(), r.decidedAt(), r.decisionReason(), r.resultDocumentId());
        }
    }

    @GetMapping("/api/v1/professional/deposits/{id}/replacements")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.read')")
    List<ReplacementResponse> replacements(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id) {
        return deposits.replacements(professional, id).stream().map(ReplacementResponse::of).toList();
    }

    @PostMapping("/api/v1/professional/deposits/{id}/replacements/{replacementId}/withdrawal")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.document')")
    ReplacementResponse withdrawReplacement(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id,
                                            @PathVariable UUID replacementId, @Valid @RequestBody ReasonBody body) {
        return ReplacementResponse.of(deposits.withdrawReplacement(professional, id, replacementId, body.reason()));
    }

    @GetMapping("/api/v1/professional/deposits/{id}/replacements/{replacementId}/content")
    @PreAuthorize("@access.canOwn(authentication, 'register.deposit.read')")
    ResponseEntity<InputStreamResource> replacementContent(@AuthenticationPrincipal AuthenticatedUser professional, @PathVariable UUID id,
                                                           @PathVariable UUID replacementId) {
        ProfessionalDepositService.ReplacementDownload download = deposits.downloadReplacement(professional, id, replacementId);
        Register.ReplacementFile f = download.file();
        return ResponseEntity.ok().headers(DocumentHeaders.attachment(f.mediaType(), f.sizeBytes(), f.originalName(), f.sha256()))
                .body(new InputStreamResource(download.content()));
    }
}
