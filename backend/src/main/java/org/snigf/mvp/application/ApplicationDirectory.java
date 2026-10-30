// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An application as other modules need it (ADR-033): its state and the document categories its
 * type version asks for. Answered only for the actor's own application, under the same
 * row-level security as everything else (V13): anyone else's is simply absent.
 */
public interface ApplicationDirectory {

    /** The actor's own application; empty when unknown or not theirs. Call inside a transaction. */
    Optional<OwnApplication> own(UUID actor, UUID applicationId);

    /**
     * A submitted application the actor may read as staff of its office (row-level security
     * decides); never open for files. Empty when unknown, a draft, or not theirs to read.
     */
    Optional<OwnApplication> forStaff(UUID actor, UUID applicationId);

    /**
     * @param openCategories the categories that take files now: every category of a draft, the
     *                       categories asked for while information is requested (E-14), none otherwise
     */
    record OwnApplication(UUID id, String reference, String state, List<String> openCategories,
                          List<DocumentCategory> categories) {

        public Optional<DocumentCategory> category(String code) {
            return categories.stream().filter(c -> c.code().equals(code)).findFirst();
        }

        public boolean acceptsDocuments() {
            return !openCategories.isEmpty();
        }
    }

    record DocumentCategory(String code, boolean required, String labelFr, String labelEn) {
    }

    /**
     * A submitted application as its own applicant or the staff of its office read it (row-level
     * security decides), with what an acknowledgment of its submission states (ADR-039). Empty for
     * a draft, an unknown application, or one the actor may not read. Call inside a transaction.
     */
    Optional<SubmittedApplication> submitted(UUID actor, UUID applicationId);

    record SubmittedApplication(UUID id, String reference, UUID applicantId, String applicantName, String typeCode,
                                String typeNameFr, String typeNameEn, String parcelReference, String officeCode,
                                Instant submittedAt) {
    }
}
