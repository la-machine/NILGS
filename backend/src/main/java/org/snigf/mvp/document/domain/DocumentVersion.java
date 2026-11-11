// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One file uploaded for a document of an application (V14): numbered by the database, never
 * changed. The bytes are in storage under {@code storageKey}; {@code sha256} proves them
 * unchanged. An upload is what the applicant sent, never verified evidence.
 *
 * <p>{@code replacement}: the information request that allowed this version to replace the earlier ones
 * (V29, ADR-041 D19), with its reason and time; null for a draft's version or a first file.
 */
public record DocumentVersion(UUID id, UUID documentId, String category, int versionNo, Status status, String storageKey,
                              String sha256, long sizeBytes, DocumentType type, String originalName, Instant uploadedAt,
                              Replacement replacement) {

    /** PENDING while a scan runs (pilot, P-02); REJECTED when a scan refused it. */
    public enum Status { PENDING, STORED, REJECTED }

    /** The request a replacing version answers: the officer's reason and when it was asked. */
    public record Replacement(UUID id, String reason, Instant requestedAt) {
    }

    public DocumentVersion(UUID id, UUID documentId, String category, int versionNo, Status status, String storageKey, String sha256,
                           long sizeBytes, DocumentType type, String originalName, Instant uploadedAt) {
        this(id, documentId, category, versionNo, status, storageKey, sha256, sizeBytes, type, originalName, uploadedAt, null);
    }
}
