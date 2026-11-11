// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.retention.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The records of D18 (V30). A class has no period: until a schedule is approved, its standing is
 * {@code NO_APPROVED_SCHEDULE_KEPT} and nothing may be disposed of. A hold covers one document, every document of a
 * deposit, or every piece of evidence of a professional; it is never deleted, and its release is recorded beside it.
 */
public final class Retention {

    private Retention() {
    }

    /** What a hold may name. The first three are single records; the last two cover what belongs to them. */
    public static final Set<String> SUBJECT_KINDS = Set.of("DEPOSIT_DOCUMENT", "DEPOSIT_DOCUMENT_REPLACEMENT", "PROFESSIONAL_EVIDENCE",
            "DEPOSIT", "PROFESSIONAL");

    /** The kinds that are one record, with a retention class of their own. */
    public static final Set<String> RECORD_KINDS = Set.of("DEPOSIT_DOCUMENT", "DEPOSIT_DOCUMENT_REPLACEMENT", "PROFESSIONAL_EVIDENCE");

    public static final int REASON_MIN = 10;
    public static final int REASON_MAX = 2000;

    public record RetentionClass(String code, String descriptionEn, String descriptionFr, String scheduleReference, LocalDate scheduleApprovedOn,
                                 String standing) {
    }

    public record Release(UUID id, UUID holdId, String reason, UUID requestedBy, String requestedByName, Instant requestedAt, String status,
                          UUID decidedBy, String decidedByName, Instant decidedAt, String decisionReason) {

        public boolean pending() {
            return "PENDING".equals(status);
        }
    }

    /** {@code inForce}: no approved release. */
    public record Hold(UUID id, String subjectKind, UUID subjectId, String reason, UUID placedBy, String placedByName, Instant placedAt,
                       boolean inForce, List<Release> releases) {

        public Hold {
            releases = List.copyOf(releases);
        }
    }

    /** One record's retention, as the database computes it: today {@code disposalAllowed} is always false. */
    public record RecordRetention(String kind, UUID id, String retentionClass, String standing, String scheduleReference,
                                  LocalDate scheduleApprovedOn, long holdsInForce, boolean disposalAllowed) {
    }
}
