// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.webauthn;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Started WebAuthn ceremonies, held on the server between the options and the response.
 * The session carries only the random ceremony id (ADR-022: no object graphs in sessions).
 * An entry is used once, by the account and for the kind it was started for, before its
 * timeout. Memory is bounded: the oldest entries go first.
 *
 * <p>One application instance is assumed (the demonstration); several instances would keep
 * ceremonies in the database.
 */
final class CeremonyStore {

    enum Kind { REGISTRATION, ASSERTION, SIGNING }

    private record Pending(Kind kind, UUID userId, Object options, Instant expiresAt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Pending> pending;
    private final Duration timeToLive;
    private final Clock clock;

    CeremonyStore(Duration timeToLive, int capacity, Clock clock) {
        this.timeToLive = timeToLive;
        this.clock = clock;
        this.pending = new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Pending> eldest) {
                return size() > capacity;
            }
        };
    }

    String put(Kind kind, UUID userId, Object options) {
        byte[] id = new byte[32];
        RANDOM.nextBytes(id);
        String ceremonyId = Base64.getUrlEncoder().withoutPadding().encodeToString(id);
        synchronized (pending) {
            pending.put(ceremonyId, new Pending(kind, userId, options, clock.instant().plus(timeToLive)));
        }
        return ceremonyId;
    }

    /** Removes and returns the options, if the ceremony exists, matches and has not expired. */
    <T> Optional<T> take(String ceremonyId, Kind kind, UUID userId, Class<T> type) {
        if (ceremonyId == null) {
            return Optional.empty();
        }
        Pending entry;
        synchronized (pending) {
            entry = pending.remove(ceremonyId);
        }
        if (entry == null || entry.kind() != kind || !entry.userId().equals(userId)
                || entry.expiresAt().isBefore(clock.instant()) || !type.isInstance(entry.options())) {
            return Optional.empty();
        }
        return Optional.of(type.cast(entry.options()));
    }
}
