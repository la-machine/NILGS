// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.domain;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.reporting.application.ReportingProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The DEMO stage targets (MVP-06): only for states a case waits in, above zero, at most a year. */
class StageTargetsTest {

    @Test
    void theSettingsGiveATargetToEveryStateACaseWaitsInExceptTheAutomaticOne() {
        ReportingProperties settings = new ReportingProperties(Duration.ofDays(5), Duration.ofDays(30), Duration.ofDays(10),
                Duration.ofDays(5), Duration.ofDays(90), 50);
        StageTargets targets = settings.targets();
        assertThat(targets.of("COMPLETENESS_REVIEW")).as("Baseline: 5 days for completeness").isEqualTo(Duration.ofDays(5));
        assertThat(targets.of("SUBMITTED")).as("moved at once by the automatic check").isNull();
        assertThat(targets.of("APPROVED")).isNull();
        assertThat(targets.inSeconds()).containsEntry("UNDER_REVIEW", 864_000L).hasSize(4);
    }

    @Test
    void aTargetIsAboveZeroAtMostAYearAndOnlyForAWorkingState() {
        assertThatThrownBy(() -> new StageTargets(Map.of("COMPLETENESS_REVIEW", Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageTargets(Map.of("COMPLETENESS_REVIEW", Duration.ofDays(-1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageTargets(Map.of("UNDER_REVIEW", Duration.ofDays(400))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageTargets(Map.of("APPROVED", Duration.ofDays(1))))
                .as("an ended application waits for nothing").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportingProperties(Duration.ofDays(5), Duration.ofDays(30), Duration.ofDays(10),
                Duration.ZERO, Duration.ofDays(90), 50)).as("checked when the settings are read").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportingProperties(Duration.ofDays(5), Duration.ofDays(30), Duration.ofDays(10),
                Duration.ofDays(5), Duration.ZERO, 50)).isInstanceOf(IllegalArgumentException.class);
    }
}
