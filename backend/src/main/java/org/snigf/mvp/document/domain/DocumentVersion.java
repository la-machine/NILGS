// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One file uploaded for a document of an application (V14): numbered by the database, never
 * changed. The bytes are in storage under {@code storageKey}; {@code sha256} proves them
 * unchanged. An upload is what the applicant sent, never verified evidence.
 */
public record DocumentVersion(UUID id, UUID documentId, String category, int versionNo, Status status, String storageKey,
                              String sha256, long sizeBytes, DocumentType type, String originalName, Instant uploadedAt) {

    /** PENDING while a scan runs (pilot, P-02); REJECTED when a scan refused it. */
    public enum Status { PENDING, STORED, REJECTED }
}
