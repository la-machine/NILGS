// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.UUID;

import org.snigf.mvp.identity.ActiveGrant;

/**
 * A special grant of one permission to one person (ADR-025). It takes effect only once a
 * second administrator approved it, and ends at the first of: its end date, its last use,
 * revocation.
 *
 * @param officeCode null for everywhere
 * @param validUntil null for no end date
 * @param maxUses    null for unlimited uses
 */
public record SpecialGrant(UUID id, UUID userId, String permission, String officeCode, String reason,
                           Instant validUntil, Integer maxUses, int uses, Status status, UUID requestedBy,
                           Instant requestedAt, UUID decidedBy, Instant decidedAt, String decisionReason,
                           UUID revokedBy, Instant revokedAt, String revokeReason) {

    public enum Status { PENDING, APPROVED, REJECTED, REVOKED }

    public boolean inForceAt(Instant now) {
        return status == Status.APPROVED
                && (validUntil == null || validUntil.isAfter(now))
                && (maxUses == null || uses < maxUses);
    }

    public ActiveGrant asActive() {
        return new ActiveGrant(id, permission, officeCode, validUntil, maxUses == null ? null : maxUses - uses);
    }
}
