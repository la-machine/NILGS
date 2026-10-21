// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.id;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008: identifiers are RFC 9562 version 7, ordered by creation time. */
class UuidV7Test {

    @Test
    void hasVersion7AndTheRfcVariant() {
        UUID id = UuidV7.next();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void carriesItsMillisecondTimestampAndSortsByIt() {
        UUID earlier = UuidV7.at(1_760_000_000_000L);
        UUID later = UuidV7.at(1_760_000_000_001L);

        assertThat(earlier.getMostSignificantBits() >>> 16).isEqualTo(1_760_000_000_000L);
        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void isRandomWithinTheSameMillisecond() {
        assertThat(UuidV7.at(1L)).isNotEqualTo(UuidV7.at(1L));
    }
}
