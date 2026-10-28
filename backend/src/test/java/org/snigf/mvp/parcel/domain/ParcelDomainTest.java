// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parcel references (Baseline Task D) and the text rules of a version (MVP-01). */
class ParcelDomainTest {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    @Test
    void aReferenceHasTheBaselineFormatAndAValidCheckCharacter() {
        ParcelReference reference = ParcelReference.issue("YDE1", 2026, 42);
        assertThat(reference.value()).matches("^DEMO-PCL-YDE1-2026-000042-[0-9A-Z]$");
        assertThat(ParcelReference.isValid(reference.value())).isTrue();
        assertThat(reference.value()).isEqualTo("DEMO-PCL-YDE1-2026-000042-1");
    }

    @Test
    void everySingleWrongCharacterIsDetected() {
        for (long sequence : new long[] {1, 42, 999_999}) {
            String good = ParcelReference.issue("DEMOIT", 2026, sequence).value();
            for (int i = 0; i < good.length(); i++) {
                if (good.charAt(i) == '-') {
                    continue;
                }
                for (char c : ALPHABET.toCharArray()) {
                    if (c == good.charAt(i)) {
                        continue;
                    }
                    String typo = good.substring(0, i) + c + good.substring(i + 1);
                    assertThat(ParcelReference.isValid(typo)).as(typo).isFalse();
                }
            }
        }
    }

    @Test
    void everySwapOfTwoAdjacentCharactersIsDetected() {
        String good = ParcelReference.issue("YDE1", 2026, 123_456).value();
        String compact = good.replace("-", "");
        for (int i = 0; i + 1 < compact.length(); i++) {
            if (compact.charAt(i) == compact.charAt(i + 1)) {
                continue;
            }
            char[] swapped = compact.toCharArray();
            swapped[i] = compact.charAt(i + 1);
            swapped[i + 1] = compact.charAt(i);
            String s = new String(swapped);
            String typed = "DEMO-PCL-" + s.substring(7, 11) + "-" + s.substring(11, 15) + "-" + s.substring(15, 21)
                    + "-" + s.charAt(21);
            if (!typed.equals(good) && typed.startsWith("DEMO-PCL-")) {
                assertThat(ParcelReference.isValid(typed)).as(typed).isFalse();
            }
        }
    }

    @Test
    void malformedReferencesAreRefusedWithoutThrowing() {
        for (String bad : new String[] {null, "", "DEMO-PCL", "demo-pcl-yde1-2026-000042-1", "DEMO-PCL-YDE1-2026-42-1",
                "DEMO-PCL-YDE1-2026-000042-1 ", "DEMO-PCL-YDE1-2026-000042-1;DROP", "../../etc/passwd"}) {
            assertThat(ParcelReference.isValid(bad)).as(String.valueOf(bad)).isFalse();
        }
        assertThatThrownBy(() -> new ParcelReference("DEMO-PCL-YDE1-2026-000042-2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParcelReference.issue("YDE1", 2026, 1_000_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionTextRules() {
        assertThat(ParcelRules.problems("Bastos, Yaoundé", null)).isEmpty();
        assertThat(ParcelRules.problems("B", null)).containsExactly("LOCALITY_INVALID");
        assertThat(ParcelRules.problems("Bastos", "x".repeat(101))).containsExactly("EXTERNAL_REFERENCE_INVALID");
        assertThat(ParcelRules.problems("Bastos\u0000", null)).containsExactly("LOCALITY_INVALID");
        assertThat(ParcelRules.validReason("too short")).isFalse();
        assertThat(ParcelRules.validReason("Boundary surveyed again on site")).isTrue();
    }

    @Test
    void theDisplayZoneFollowsTheCentroid() {
        assertThat(ParcelRules.displaySrid(11.5)).isEqualTo(32632);
        assertThat(ParcelRules.displaySrid(12.0)).isEqualTo(32633);
        assertThat(ParcelRules.displaySrid(14.2)).isEqualTo(32633);
    }
}
