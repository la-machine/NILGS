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
 * @param requested    the document categories asked for (an information request only)
 * @param actorName    who acted; null for the automatic check
 * @param replacements the documents an information request asks to be replaced, each with its reason (V29,
 *                     ADR-041 D19); a requested category without a document yet is not a replacement
 */
public record CompletenessRecord(UUID id, Kind kind, List<String> missing, List<String> requested, String message,
                                 String actorName, Instant at, List<Replacement> replacements) {

    public enum Kind { AUTOMATIC, COMPLETE, INFO_REQUESTED, RESPONSE }

    /** A document named for replacement by its category (one document per category), and why. */
    public record Replacement(String category, String reason) {
    }

    public CompletenessRecord {
        missing = missing == null ? List.of() : List.copyOf(missing);
        requested = requested == null ? List.of() : List.copyOf(requested);
        replacements = replacements == null ? List.of() : List.copyOf(replacements);
    }

    public CompletenessRecord(UUID id, Kind kind, List<String> missing, List<String> requested, String message, String actorName, Instant at) {
        this(id, kind, missing, requested, message, actorName, at, List.of());
    }

    /** Bounds of an information request's message (V15). */
    public static final int MESSAGE_MIN = 10;
    public static final int MESSAGE_MAX = 2000;
}
