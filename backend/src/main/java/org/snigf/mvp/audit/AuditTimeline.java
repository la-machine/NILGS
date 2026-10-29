// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A case's or a parcel's history, from the audit trail alone (E-18, ADR-038): every event the
 * reader may read that has it as a subject, oldest first.
 *
 * @param reference the reference the trail names it by; null when it was asked for by id and has none
 * @param truncated more events exist than {@link #MAX_EVENTS}
 */
public record AuditTimeline(String subjectType, UUID subjectId, String reference, List<AuditEventView> events,
                            boolean truncated) {

    /** The kinds of record a timeline is about. */
    public static final Set<String> SUBJECT_TYPES = Set.of("APPLICATION", "PARCEL");
    public static final int MAX_EVENTS = 500;
}
