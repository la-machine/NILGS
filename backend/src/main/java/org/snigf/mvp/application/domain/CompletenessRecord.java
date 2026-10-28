// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One step of the completeness review (MVP-03, V15), never changed: the automatic check, an
 * officer's acceptance or information request, the applicant's response. {@code missing} is
 * what the database found missing at that moment ({@code MISSING:statement},
 * {@code MISSING_DOCUMENT:DEMO_IDENTITY}).
 *
 * @param requested the document categories asked for (an information request only)
 * @param actorName who acted; null for the automatic check
 */
public record CompletenessRecord(UUID id, Kind kind, List<String> missing, List<String> requested, String message,
                                 String actorName, Instant at) {

    public enum Kind { AUTOMATIC, COMPLETE, INFO_REQUESTED, RESPONSE }

    public CompletenessRecord {
        missing = missing == null ? List.of() : List.copyOf(missing);
        requested = requested == null ? List.of() : List.copyOf(requested);
    }

    /** Bounds of an information request's message (V15). */
    public static final int MESSAGE_MIN = 10;
    public static final int MESSAGE_MAX = 2000;
}
