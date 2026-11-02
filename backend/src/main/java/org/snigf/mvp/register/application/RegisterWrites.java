// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.shared.idempotency.IdempotencyStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every register write shares, for the counter's and the professional's use cases alike: the
 * database's refusal turned into a code and audited on its own (the refused transaction rolls back),
 * and creates made idempotent by {@code Idempotency-Key}. Not transactional: it runs in the caller's
 * transaction.
 */
@Component
class RegisterWrites {

    private final AuditRecorder audit;
    private final IdempotencyStore idempotency;
    private final JsonMapper json;

    RegisterWrites(AuditRecorder audit, IdempotencyStore idempotency, JsonMapper json) {
        this.audit = audit;
        this.idempotency = idempotency;
        this.json = json;
    }

    /** Runs a write; the database's refusal becomes a precise code and an independent audit event. */
    <T> T guarded(String entity, UUID entityId, String operation, Supplier<T> write) {
        try {
            return write.get();
        } catch (DataAccessException e) {
            RegisterRefused refused = RegisterRefused.translated(e);
            audit.recordIndependently(AuditEvent.of(AuditAction.REGISTER_REFUSED, AuditOutcome.FAILED).withEntity(entity, entityId)
                    .withReason(refused.code()).withDetails(Map.of("code", refused.code(), "operation", operation)));
            throw refused;
        }
    }

    /** A refusal decided before the database, audited on its own like the database's. */
    RegisterRefused refusedIndependently(String entity, UUID id, RegisterRefused refused) {
        audit.recordIndependently(AuditEvent.of(AuditAction.REGISTER_REFUSED, AuditOutcome.FAILED).withEntity(entity, id)
                .withReason(refused.code()).withDetails(Map.of("code", refused.code())));
        return refused;
    }

    String requestHash(String idempotencyKey, Object request) {
        requireKey(idempotencyKey);
        return IdempotencyStore.sha256(json.writeValueAsString(request));
    }

    static void requireKey(String idempotencyKey) {
        if (!IdempotencyStore.validKey(idempotencyKey)) {
            throw new RegisterRefused(RegisterRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REQUIRED");
        }
    }

    /** The id an earlier request with this key created, if there was one; the same key for another request is refused. */
    java.util.Optional<UUID> earlier(UUID user, String idempotencyKey, String hash) {
        return idempotency.find(user, idempotencyKey).map(stored -> {
            if (!stored.requestSha256().equals(hash)) {
                throw new RegisterRefused(RegisterRefused.Kind.INVALID, "IDEMPOTENCY_KEY_REUSED");
            }
            return json.readValue(stored.body(), Created.class).id();
        });
    }

    void remember(UUID user, String key, String endpoint, String hash, UUID id) {
        try {
            idempotency.save(user, key, endpoint, hash, 201, json.writeValueAsString(new Created(id)));
        } catch (IdempotencyStore.InProgress e) {
            throw new RegisterRefused(RegisterRefused.Kind.CONFLICT, "REQUEST_IN_PROGRESS");
        }
    }

    /** What an idempotent create stored. */
    private record Created(UUID id) {
    }
}
