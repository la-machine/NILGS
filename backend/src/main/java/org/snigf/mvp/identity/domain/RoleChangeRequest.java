// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.snigf.mvp.shared.security.Permission.Scope;

/**
 * A proposed change to a custom role, applied only when another administrator approves it
 * (ADR-025).
 *
 * @param proposal the role as it would be after the change (ignored for RETIRE)
 */
public record RoleChangeRequest(UUID id, Kind kind, String roleCode, Proposal proposal, String reason,
                                UUID requestedBy, Instant requestedAt, Status status, UUID decidedBy,
                                Instant decidedAt, String decisionReason) {

    public enum Kind { CREATE, UPDATE, RETIRE }

    public enum Status { PENDING, APPROVED, REJECTED }

    /** Names, description and permissions proposed for the role. */
    public record Proposal(String nameFr, String nameEn, String description, Map<String, Scope> permissions) {

        public Proposal {
            permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
        }
    }
}
