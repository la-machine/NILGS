// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * An audit event as an auditor reads it: every stored column, with the chain hashes in
 * hex so a reader can check the links by hand.
 *
 * @param detailsJson the {@code details} JSON object, as stored
 * @param actorName   the acting account's current name (for reading only: the event records its id); null for none
 * @param subjects    what the event is about, with the office of each at the time (ADR-038); empty for none
 * @param intact      the event's hash, recomputed from its own row and its stored link, equals the stored one
 */
public record AuditEventView(long chainPos, UUID id, Instant occurredAt, String actorType, UUID actorId,
                             String actorLabel, List<String> actorRoles, String action, String entityType,
                             UUID entityId, String entityRef, String outcome, String reason, UUID correlationId,
                             String sourceIp, String detailsJson, String prevHash, String hash, String actorName,
                             List<Subject> subjects, boolean intact) {

    /**
     * @param officeCode the office of the record when the event was recorded; null for none
     */
    public record Subject(String type, UUID id, String officeCode) {
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** One page of a search, newest first. */
    public record Page(List<AuditEventView> events, int page, int size, boolean more) {
    }
}
