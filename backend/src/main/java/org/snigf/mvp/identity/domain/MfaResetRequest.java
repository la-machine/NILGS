// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A request to reset a person's second factor (ADR-028): asked by an administrator,
 * confirmed by the person's supervisor, who records how the identity was checked. The
 * database performs the reset when it is confirmed.
 *
 * @param offices        offices where the person holds an active role: a supervisor holding
 *                       {@code identity.reset.confirm} at one of them may decide
 * @param identityCheck  how the supervisor checked the identity (set on confirmation)
 */
public record MfaResetRequest(UUID id, UUID userId, String displayName, String email, List<String> offices,
                              String reason, UUID requestedBy, Instant requestedAt, Status status, UUID decidedBy,
                              Instant decidedAt, String identityCheck, String decisionReason) {

    public enum Status { PENDING, CONFIRMED, REJECTED }

    public MfaResetRequest {
        offices = List.copyOf(offices);
    }
}
