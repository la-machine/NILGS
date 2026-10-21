// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.snigf.mvp.audit.AuditEvent;

/**
 * The append-only chain. Implementations can only insert and read: position, time and
 * hashes are assigned by the store (V3__audit.sql), never chosen here.
 */
public interface AuditEventRepository {

    /**
     * Appends one event and returns its position.
     *
     * @param detailsJson {@code event.details()} already serialised as a JSON object
     */
    long append(UUID id, Actor actor, AuditEvent event, UUID correlationId, String sourceIp, String detailsJson);

    Optional<ChainHead> head();

    Optional<byte[]> hashAt(long chainPos);

    /** Every event between two positions (inclusive), in chain order. */
    void forEach(long fromPos, long toPos, Consumer<ChainedEvent> action);
}
