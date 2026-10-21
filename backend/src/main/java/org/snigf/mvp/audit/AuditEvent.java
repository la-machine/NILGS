// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An event as a service describes it. It has no actor, time, address or correlation
 * field: those come from the security context and the request, never from the caller
 * (Slice 1 design §7). Position, time and hashes are assigned by the database.
 *
 * <p>{@code details} is stored as a JSON object. It must never contain a password, code,
 * token, key or full geometry; put identifiers and short facts only.
 *
 * @param action     what happened
 * @param outcome    result of the attempt
 * @param entityType kind of record concerned, upper snake case ({@code PARCEL}), or null
 * @param entityId   identifier of that record, or null
 * @param entityRef  human reference of that record (parcel number), at most 64 characters
 * @param reason     free-text justification given by the actor, at most 1000 characters
 * @param details    further facts, serialised as a JSON object
 */
public record AuditEvent(
        AuditAction action,
        AuditOutcome outcome,
        String entityType,
        UUID entityId,
        String entityRef,
        String reason,
        Map<String, Object> details) {

    private static final Pattern ENTITY_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    public AuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        if (entityType != null && !ENTITY_TYPE.matcher(entityType).matches()) {
            throw new IllegalArgumentException("entity type must be upper snake case: " + entityType);
        }
        if (entityRef != null && (entityRef.isEmpty() || entityRef.length() > 64)) {
            throw new IllegalArgumentException("entity reference must have 1 to 64 characters");
        }
        if (reason != null && reason.length() > 1000) {
            throw new IllegalArgumentException("reason must have at most 1000 characters");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditEvent of(AuditAction action, AuditOutcome outcome) {
        return new AuditEvent(action, outcome, null, null, null, null, Map.of());
    }

    public AuditEvent withEntity(String type, UUID id) {
        return new AuditEvent(action, outcome, type, id, entityRef, reason, details);
    }

    public AuditEvent withEntityRef(String ref) {
        return new AuditEvent(action, outcome, entityType, entityId, ref, reason, details);
    }

    public AuditEvent withReason(String text) {
        return new AuditEvent(action, outcome, entityType, entityId, entityRef, text, details);
    }

    public AuditEvent withDetails(Map<String, Object> facts) {
        return new AuditEvent(action, outcome, entityType, entityId, entityRef, reason, facts);
    }
}
