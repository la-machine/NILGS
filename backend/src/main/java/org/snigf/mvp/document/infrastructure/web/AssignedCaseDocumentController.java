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
 * The documents of a case for reviewers and decision authorities (ADR-035): the cases assigned to
 * them or waiting for them at their stage, as row-level security shows them. Read only; every
 * download is audited and checked against its hash.
 */
@RestController
@RequestMapping("/api/v1/assigned-cases/{id}/documents")
class AssignedCaseDocumentController {

    private final DocumentService documents;
    private final DocumentProperties settings;

    AssignedCaseDocumentController(DocumentService documents, DocumentProperties settings) {
        this.documents = documents;
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("@access.canAssigned(authentication, 'application.read')")
    DocumentController.DocumentsResponse list(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id) {
        return DocumentController.response(documents.listForStaff(staff, id), settings.maxBytes());
    }

    @GetMapping("/{versionId}/content")
    @PreAuthorize("@access.canAssigned(authentication, 'application.read')")
    ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal AuthenticatedUser staff, @PathVariable UUID id,
                                                 @PathVariable UUID versionId) {
        return DocumentController.attachment(documents.downloadForStaff(staff, id, versionId));
    }
}
