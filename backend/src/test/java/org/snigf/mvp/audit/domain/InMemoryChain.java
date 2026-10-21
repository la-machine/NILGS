// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import org.snigf.mvp.audit.AuditEvent;

/**
 * An audit chain held in memory, chained exactly like the database trigger, with the
 * tampering operations of the residual-risk actor (docs/audit.md).
 */
final class InMemoryChain implements AuditEventRepository {

    private final TreeMap<Long, ChainedEvent> rows = new TreeMap<>();

    InMemoryChain withEvents(int count) {
        for (int i = 0; i < count; i++) {
            add("{\"n\": " + i + "}");
        }
        return this;
    }

    ChainedEvent add(String details) {
        long pos = rows.isEmpty() ? 1 : rows.lastKey() + 1;
        byte[] prev = rows.isEmpty() ? ChainedEvent.GENESIS : rows.lastEntry().getValue().hash();
        ChainedEvent event = sealed(pos, prev, details);
        rows.put(pos, event);
        return event;
    }

    /** Edits an event's content without touching its hashes. */
    void edit(long pos, String details) {
        ChainedEvent e = rows.get(pos);
        rows.put(pos, copy(e, details, e.prevHash(), e.hash()));
    }

    /** Edits an event and recomputes its own hash, as an administrator would. */
    void editAndRehash(long pos, String details) {
        ChainedEvent e = rows.get(pos);
        ChainedEvent edited = copy(e, details, e.prevHash(), e.hash());
        rows.put(pos, copy(edited, details, e.prevHash(), edited.computeHash()));
    }

    /** Edits an event and recomputes every hash after it: the chain alone no longer shows it. */
    void rewriteFrom(long pos, String details) {
        editAndRehash(pos, details);
        for (long p = pos + 1; rows.containsKey(p); p++) {
            ChainedEvent e = rows.get(p);
            byte[] prev = rows.get(p - 1).hash();
            ChainedEvent relinked = copy(e, e.details(), prev, e.hash());
            rows.put(p, copy(relinked, e.details(), prev, relinked.computeHash()));
        }
    }

    void delete(long pos) {
        rows.remove(pos);
    }

    @Override
    public long append(UUID id, Actor actor, AuditEvent event, UUID correlationId, String sourceIp, String detailsJson) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ChainHead> head() {
        return rows.isEmpty() ? Optional.empty()
                : Optional.of(new ChainHead(rows.lastKey(), rows.lastEntry().getValue().hash()));
    }

    @Override
    public Optional<byte[]> hashAt(long chainPos) {
        return Optional.ofNullable(rows.get(chainPos)).map(ChainedEvent::hash);
    }

    @Override
    public void forEach(long fromPos, long toPos, Consumer<ChainedEvent> action) {
        new ArrayList<>(rows.subMap(fromPos, true, toPos, true).values()).forEach(action);
    }

    private static ChainedEvent sealed(long pos, byte[] prev, String details) {
        ChainedEvent draft = new ChainedEvent(pos, UUID.randomUUID(), Instant.parse("2026-10-21T08:00:00.123456Z"),
                "SERVICE", null, "test", List.of(), "TEST_EVENT", null, null, null, "SUCCESS", null,
                UUID.randomUUID(), null, details, prev, new byte[32]);
        return copy(draft, details, prev, draft.computeHash());
    }

    private static ChainedEvent copy(ChainedEvent e, String details, byte[] prev, byte[] hash) {
        return new ChainedEvent(e.chainPos(), e.id(), e.occurredAt(), e.actorType(), e.actorId(), e.actorLabel(),
                e.actorRoles(), e.action(), e.entityType(), e.entityId(), e.entityRef(), e.outcome(), e.reason(),
                e.correlationId(), e.sourceIp(), details, prev, hash);
    }
}
