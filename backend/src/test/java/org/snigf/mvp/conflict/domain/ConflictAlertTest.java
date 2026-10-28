// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The resolution rules an alert carries itself (MVP-04, C6b), before the database checks them again. */
class ConflictAlertTest {

    static final UUID AUTHOR_A = UUID.randomUUID();
    static final UUID AUTHOR_B = UUID.randomUUID();

    static ConflictAlert alert(String officeA, String officeB) {
        return new ConflictAlert(UUID.randomUUID(), ConflictAlert.Type.OVERLAP, ConflictAlert.Status.OPEN,
                new ConflictAlert.Side(UUID.randomUUID(), "A", officeA, UUID.randomUUID(), 1, 1, AUTHOR_A, "Author A",
                        new BigDecimal("900"), new BigDecimal("0.66667")),
                new ConflictAlert.Side(UUID.randomUUID(), "B", officeB, UUID.randomUUID(), 2, 2, AUTHOR_B, "Author B",
                        new BigDecimal("900"), new BigDecimal("0.66667")),
                new BigDecimal("600"), "{}", null, UUID.randomUUID(), Instant.now(),
                null, null, null, null, null, null, null, 0);
    }

    @Test
    void theAuthorOfEitherComparedVersionIsTheAuthorAndNobodyElse() {
        ConflictAlert alert = alert("YDE1", "YDE1");
        assertThat(alert.authoredBy(AUTHOR_A)).isTrue();
        assertThat(alert.authoredBy(AUTHOR_B)).isTrue();
        assertThat(alert.authoredBy(UUID.randomUUID())).isFalse();
    }

    @Test
    void theOfficesAreThoseOfBothParcelsOnceEach() {
        assertThat(alert("YDE1", "YDE1").offices()).containsExactly("YDE1");
        assertThat(alert("YDE1", "DLA1").offices()).containsExactly("YDE1", "DLA1");
    }

    @Test
    void aResolutionNeedsAnOutcomeAndANoteOfTenToTwoThousandCharacters() {
        assertThat(ConflictAlert.problems(ConflictAlert.Resolution.NO_CONFLICT, "Checked on site")).isEmpty();
        assertThat(ConflictAlert.problems(null, "Checked on site")).containsExactly("RESOLUTION_REQUIRED");
        assertThat(ConflictAlert.problems(ConflictAlert.Resolution.NO_CONFLICT, "   too short   ")).containsExactly("NOTE_REQUIRED");
        assertThat(ConflictAlert.problems(ConflictAlert.Resolution.NO_CONFLICT, null)).containsExactly("NOTE_REQUIRED");
        assertThat(ConflictAlert.problems(ConflictAlert.Resolution.NO_CONFLICT, "x".repeat(2000))).isEmpty();
        assertThat(ConflictAlert.problems(ConflictAlert.Resolution.NO_CONFLICT, "x".repeat(2001))).containsExactly("NOTE_TOO_LONG");
    }
}
