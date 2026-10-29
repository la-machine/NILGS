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

    /**
     * Events matching the filter that the actor of the transaction may read
     * ({@code audit_readable}), newest first; {@code limit} rows from {@code offset}.
     */
    java.util.List<ChainedEvent> search(org.snigf.mvp.audit.AuditEventFilter filter, int limit, int offset);

    /** The record the trail names by this reference: the id of its first event of that type with it. */
    Optional<UUID> subjectByReference(String subjectType, String reference);

    /** The reference the trail records the subject under, if any. */
    Optional<String> referenceOf(String subjectType, UUID subjectId);

    /** Every readable event with this subject, oldest first, at most {@code limit}. */
    java.util.List<ChainedEvent> timeline(String subjectType, UUID subjectId, int limit);

    /** Current names of the accounts, for reading only. */
    java.util.Map<UUID, String> accountNames(java.util.Collection<UUID> ids);

    /** Codes of the offices, for reading only. */
    java.util.Map<UUID, String> officeCodes();

    /** Every event between two positions (inclusive), in chain order. */
    void forEach(long fromPos, long toPos, Consumer<ChainedEvent> action);
}
