// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What an auditor or a supervisor searches for (Slice 1 design §5, {@code GET /audit/events}).
 * Every criterion is optional; results are newest first, {@code size} per page (1 to 100), and
 * only events the reader may read (ADR-038: everywhere, or at the office of one of their
 * subjects).
 *
 * @param entityType  record kind ({@code PARCEL}, {@code USER}, ...)
 * @param entityId    record id
 * @param subjectType the case or parcel an event is about ({@code APPLICATION}, {@code PARCEL}); with {@code subjectId}
 * @param subjectId   its id
 * @param actorId     acting user
 * @param action      action name
 * @param from        occurred at or after
 * @param to          occurred before
 * @param page        page number from 0
 * @param size        events per page
 */
public record AuditEventFilter(String entityType, UUID entityId, String subjectType, UUID subjectId, UUID actorId,
                               String action, Instant from, Instant to, int page, int size) {

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    public AuditEventFilter {
        if (entityType != null && !NAME.matcher(entityType).matches()) {
            throw new IllegalArgumentException("entityType");
        }
        if ((subjectType == null) != (subjectId == null)
                || (subjectType != null && !AuditTimeline.SUBJECT_TYPES.contains(subjectType))) {
            throw new IllegalArgumentException("subjectType and subjectId");
        }
        if (action != null && !NAME.matcher(action).matches()) {
            throw new IllegalArgumentException("action");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (page < 0 || page > 10_000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size out of range");
        }
    }
}
