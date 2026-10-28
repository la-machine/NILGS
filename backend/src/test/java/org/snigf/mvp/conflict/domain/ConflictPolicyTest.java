// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The detection thresholds accept only what the database accepts (V11). */
class ConflictPolicyTest {

    private static ConflictPolicy policy(String min, String share) {
        return new ConflictPolicy(new BigDecimal(min), new BigDecimal(share));
    }

    @Test
    void theBaselineThresholdsAreValid() {
        assertThat(policy("1.00", "0.95").problems()).isEmpty();
        assertThat(policy("0.01", "0.5").problems()).isEmpty();
        assertThat(policy("1000", "1").problems()).isEmpty();
    }

    @Test
    void valuesOutsideTheDatabaseBoundsAreRefused() {
        assertThat(policy("0", "0.95").problems()).containsExactly("MIN_OVERLAP_OUT_OF_RANGE");
        assertThat(policy("1000.01", "0.95").problems()).containsExactly("MIN_OVERLAP_OUT_OF_RANGE");
        assertThat(policy("1.005", "0.95").problems()).as("two decimals at most").containsExactly("MIN_OVERLAP_OUT_OF_RANGE");
        assertThat(policy("1", "0.49").problems()).containsExactly("DUPLICATE_SHARE_OUT_OF_RANGE");
        assertThat(policy("1", "1.01").problems()).containsExactly("DUPLICATE_SHARE_OUT_OF_RANGE");
        assertThat(new ConflictPolicy(null, null).problems()).hasSize(2);
    }

    @Test
    void trailingZerosDoNotMakeADifferentPolicy() {
        assertThat(policy("1.00", "0.950").sameAs(policy("1", "0.95"))).isTrue();
        assertThat(policy("1.00", "0.95").sameAs(policy("2", "0.95"))).isFalse();
    }
}
