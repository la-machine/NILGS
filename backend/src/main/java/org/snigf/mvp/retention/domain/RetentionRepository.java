// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.retention.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retention data under row-level security (V30): every read needs {@code records.retention.read}, and every write is
 * a database function that checks its own permission and the four eyes of a release.
 */
public interface RetentionRepository {

    List<Retention.RetentionClass> classes();

    /** The holds naming this subject, or every hold when both are null, newest first, with their releases. */
    List<Retention.Hold> holds(String subjectKind, UUID subjectId, int limit, int offset);

    Optional<Retention.Hold> hold(UUID id);

    Optional<Retention.RecordRetention> retentionOf(String kind, UUID id);

    UUID place(String subjectKind, UUID subjectId, String reason);

    UUID requestRelease(UUID holdId, String reason);

    void decideRelease(UUID releaseId, String outcome, String reason);
}
