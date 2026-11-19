// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.procedure.application.PartyService;
import org.snigf.mvp.procedure.domain.Parties;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The persons of a case at intake (ADR-043 step 5a). Read by the case's readers and by the officers who record or check
 * persons at its office; recorded with {@code application.party.record}; checked with {@code application.party.check},
 * never by the recorder. The service and the database (V35) decide the rest. Nothing here verifies an identity.
 */
@RestController
@RequestMapping("/api/v1/procedures/{id}/parties")
class PartyController {

    static final String READ = ProcedureController.READ
            + " or @access.canAt(authentication, 'application.party.record', @caseOffices.of(#id))"
            + " or @access.canAt(authentication, 'application.party.check', @caseOffices.of(#id))";

    private final PartyService parties;

    PartyController(PartyService parties) {
        this.parties = parties;
    }

    record IdentityBody(@Size(max = 200) String fullName, @Size(max = 40) String identityKind, @Size(max = 64) String identityNumber,
                        @Size(max = 24) String phone, @Size(max = 300) String postalAddress, java.time.LocalDate validUntil, UUID sourceFile,
                        UUID sourceVersion, UUID supersedes, @Size(max = 2000) String correctionReason) {
    }

    /** {@code usufructTerm}: {@code LIFE} or {@code UNTIL} with {@code usufructUntil}, for a usufruct only. */
    record ShareBody(UUID person, @Size(max = 20) String interest, int shareNum, int shareDen, @Size(max = 8) String usufructTerm,
                     java.time.LocalDate usufructUntil, UUID basisFile, UUID basisVersion) {
    }

    record ExceptionBody(UUID identityVersion, @Size(max = 40) String policy, @Size(max = 2000) String reason) {
    }

    record OwnershipBody(@Size(max = 20) List<@Valid ShareBody> shares, UUID supersedes, @Size(max = 2000) String correctionReason) {
    }

    record MandateBody(@Size(max = 20) List<UUID> represents, @Size(max = 20) String scope, UUID documentFile, UUID supersedes,
                       @Size(max = 2000) String correctionReason) {
    }

    record CheckBody(@Size(max = 20) String targetKind, UUID targetId, @Size(max = 20) String outcome, @Size(max = 1000) String note) {
    }

    @GetMapping
    @PreAuthorize(READ)
    Parties.View read(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return parties.read(staff, id);
    }

    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.canAt(authentication, 'application.party.record', @caseOffices.of(#id))")
    Parties.View addFile(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id, @RequestParam("purpose") String purpose,
                         @RequestPart("file") MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return parties.addFile(officer, id, purpose, file.getOriginalFilename(), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/files/{fileId}")
    @PreAuthorize(READ)
    ResponseEntity<InputStreamResource> file(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id, @PathVariable UUID fileId) {
        PartyService.FileDownload d = parties.download(staff, id, fileId);
        Parties.File f = d.file();
        return ResponseEntity.ok().headers(ProcedureController.attachment(f.mediaType(), f.sizeBytes(), f.originalName(), f.sha256()))
                .body(new InputStreamResource(d.content()));
    }

    @PostMapping("/persons/{personId}/identity")
    @PreAuthorize("@access.canAt(authentication, 'application.party.record', @caseOffices.of(#id))")
    Parties.View identity(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id, @PathVariable UUID personId,
                          @Valid @RequestBody IdentityBody body) {
        return parties.recordIdentity(officer, id, personId, new Parties.NewIdentity(body.fullName(), body.identityKind(), body.identityNumber(),
                blank(body.phone()), blank(body.postalAddress()), body.validUntil()), body.sourceFile(), body.sourceVersion(), body.supersedes(),
                blank(body.correctionReason()));
    }

    @PostMapping("/ownership")
    @PreAuthorize("@access.canAt(authentication, 'application.party.record', @caseOffices.of(#id))")
    Parties.View ownership(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id, @Valid @RequestBody OwnershipBody body) {
        List<Parties.NewShare> shares = body.shares() == null ? List.of() : body.shares().stream()
                .map(s -> new Parties.NewShare(s.person(), s.interest(), s.shareNum(), s.shareDen(), blank(s.usufructTerm()), s.usufructUntil(),
                        s.basisFile(), s.basisVersion())).toList();
        return parties.recordOwnership(officer, id, shares, body.supersedes(), blank(body.correctionReason()));
    }

    @PostMapping("/persons/{personId}/mandate")
    @PreAuthorize("@access.canAt(authentication, 'application.party.record', @caseOffices.of(#id))")
    Parties.View mandate(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id, @PathVariable UUID personId,
                         @Valid @RequestBody MandateBody body) {
        return parties.recordMandate(officer, id, personId, body.represents(), body.scope(), body.documentFile(), body.supersedes(),
                blank(body.correctionReason()));
    }

    @PostMapping("/checks")
    @PreAuthorize("@access.canAt(authentication, 'application.party.check', @caseOffices.of(#id))")
    Parties.View check(@AuthenticationPrincipal AuthenticatedUser officer, @PathVariable UUID id, @Valid @RequestBody CheckBody body) {
        return parties.check(officer, id, body.targetKind(), body.targetId(), body.outcome(), blank(body.note()));
    }

    /** A supervisor's documented exception for an expired identity document (ADR-043, DEMO policy). */
    @PostMapping("/exceptions")
    @PreAuthorize("@access.canAt(authentication, 'application.party.exception', @caseOffices.of(#id))")
    Parties.View exception(@AuthenticationPrincipal AuthenticatedUser supervisor, @PathVariable UUID id, @Valid @RequestBody ExceptionBody body) {
        return parties.authoriseException(supervisor, id, body.identityVersion(), body.policy(), blank(body.reason()));
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
