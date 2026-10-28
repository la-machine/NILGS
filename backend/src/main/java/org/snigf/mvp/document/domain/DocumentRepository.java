// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Documents under row-level security (V14): only what the named actor may see exists; a version
 * is added only by the owner, to their draft, in a category of its type.
 */
public interface DocumentRepository {

    /** The document of this category, created if it does not exist yet. */
    UUID documentFor(UUID applicationId, String category);

    int versionCount(UUID documentId);

    /** What a new version records; the database numbers it. */
    record NewVersion(UUID documentId, String storageKey, String sha256, long sizeBytes, DocumentType type,
                      String originalName, UUID uploadedBy) {
    }

    DocumentVersion add(NewVersion version);

    /** Every version of the application's documents, by category then newest first. */
    List<DocumentVersion> ofApplication(UUID applicationId);

    Optional<DocumentVersion> version(UUID applicationId, UUID versionId);

    /** Which of these storage keys a version still names (for the cleanup, no actor needed). */
    Set<String> knownKeys(Collection<String> keys);
}
