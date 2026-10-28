// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.web;

import java.util.UUID;

import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.application.DocumentService;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The documents of a submitted application, for staff reading applications at its office
 * (E-14: the intake officer judges completeness from them). Read only; every download is
 * audited and checked against its hash, as for the applicant.
 */
@RestController
@RequestMapping("/api/v1/cases/{id}/documents")
class CaseDocumentController {

    private final DocumentService documents;
    private final DocumentProperties settings;

    CaseDocumentController(DocumentService documents, DocumentProperties settings) {
        this.documents = documents;
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("@access.canAt(authentication, 'application.read', @caseOffices.of(#id))")
    DocumentController.DocumentsResponse list(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return DocumentController.response(documents.listForStaff(staff, id), settings.maxBytes());
    }

    @GetMapping("/{versionId}/content")
    @PreAuthorize("@access.canAt(authentication, 'application.read', @caseOffices.of(#id))")
    ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id,
                                                 @PathVariable UUID versionId) {
        return DocumentController.attachment(documents.downloadForStaff(staff, id, versionId));
    }
}
