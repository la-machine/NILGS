// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to assign a role to a staff member at an office (ADR-026). The assignment exists
 * only once a second administrator approves it; the database creates it in the same
 * statement as the approval.
 */
public record RoleAssignmentRequest(UUID id, UUID userId, String role, String officeCode, String reason,
                                    UUID requestedBy, Instant requestedAt, Status status, UUID decidedBy,
                                    Instant decidedAt, String decisionReason) {

    public enum Status { PENDING, APPROVED, REJECTED }
}
