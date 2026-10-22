// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.webauthn;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A started WebAuthn ceremony is finished once, by its account, for its kind, in time. */
class CeremonyStoreTest {

    static final UUID USER = UUID.randomUUID();

    /** A clock the test moves forward. */
    static final class Manual extends Clock {
        Instant now = Instant.parse("2026-10-21T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aCeremonyIsUsedOnce() {
        CeremonyStore store = new CeremonyStore(Duration.ofMinutes(2), 10, new Manual());
        String id = store.put(CeremonyStore.Kind.ASSERTION, USER, "options");

        assertThat(store.take(id, CeremonyStore.Kind.ASSERTION, USER, String.class)).contains("options");
        assertThat(store.take(id, CeremonyStore.Kind.ASSERTION, USER, String.class)).isEmpty();
    }

    @Test
    void aCeremonyBelongsToItsAccountAndKindAndIsConsumedByAnyAttempt() {
        CeremonyStore store = new CeremonyStore(Duration.ofMinutes(2), 10, new Manual());
        String id = store.put(CeremonyStore.Kind.REGISTRATION, USER, "options");

        assertThat(store.take(id, CeremonyStore.Kind.REGISTRATION, UUID.randomUUID(), String.class)).isEmpty();
        assertThat(store.take(id, CeremonyStore.Kind.REGISTRATION, USER, String.class))
                .as("a wrong attempt burns the ceremony").isEmpty();

        String other = store.put(CeremonyStore.Kind.REGISTRATION, USER, "options");
        assertThat(store.take(other, CeremonyStore.Kind.ASSERTION, USER, String.class)).isEmpty();
        assertThat(store.take(null, CeremonyStore.Kind.ASSERTION, USER, String.class)).isEmpty();
    }

    @Test
    void aCeremonyExpires() {
        Manual clock = new Manual();
        CeremonyStore store = new CeremonyStore(Duration.ofMinutes(2), 10, clock);
        String id = store.put(CeremonyStore.Kind.ASSERTION, USER, "options");
        clock.now = clock.now.plus(Duration.ofMinutes(2).plusSeconds(1));

        assertThat(store.take(id, CeremonyStore.Kind.ASSERTION, USER, String.class)).isEmpty();
    }

    @Test
    void memoryIsBoundedAndIdsAreUnguessable() {
        CeremonyStore store = new CeremonyStore(Duration.ofMinutes(2), 2, new Manual());
        String oldest = store.put(CeremonyStore.Kind.ASSERTION, USER, "1");
        store.put(CeremonyStore.Kind.ASSERTION, USER, "2");
        String newest = store.put(CeremonyStore.Kind.ASSERTION, USER, "3");

        assertThat(store.take(oldest, CeremonyStore.Kind.ASSERTION, USER, String.class)).isEmpty();
        assertThat(store.take(newest, CeremonyStore.Kind.ASSERTION, USER, String.class)).contains("3");
        assertThat(newest).hasSize(43).matches("[A-Za-z0-9_-]+");   // 256 random bits
    }
}
