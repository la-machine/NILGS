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
 */
public record AuditEventView(long chainPos, UUID id, Instant occurredAt, String actorType, UUID actorId,
                             String actorLabel, List<String> actorRoles, String action, String entityType,
                             UUID entityId, String entityRef, String outcome, String reason, UUID correlationId,
                             String sourceIp, String detailsJson, String prevHash, String hash) {

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** One page of a search, newest first. */
    public record Page(List<AuditEventView> events, int page, int size, boolean more) {
    }
}
