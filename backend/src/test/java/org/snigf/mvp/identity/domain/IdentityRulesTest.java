// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.snigf.mvp.identity.domain.PasswordPolicy.Violation.COMMONLY_USED;
import static org.snigf.mvp.identity.domain.PasswordPolicy.Violation.CONTAINS_ACCOUNT_NAME;
import static org.snigf.mvp.identity.domain.PasswordPolicy.Violation.CONTROL_CHARACTER;
import static org.snigf.mvp.identity.domain.PasswordPolicy.Violation.TOO_LONG;
import static org.snigf.mvp.identity.domain.PasswordPolicy.Violation.TOO_SHORT;

/** Password policy P7, e-mail normalisation and lockout P6, without Spring. */
class IdentityRulesTest {

    private final PasswordPolicy policy = new PasswordPolicy(12, 128, Set.of("q1w2e3r4t5y6", "iloveyou2026"));
    private final EmailAddress email = EmailAddress.of("mballa.ndongo@demo.invalid");

    @Test
    void acceptsALongUncommonPasswordUnrelatedToTheAccount() {
        assertThat(policy.check("Correct-Horse-Battery-42", email, "Awa Demo")).isEmpty();
    }

    @Test
    void reportsEveryBrokenRule() {
        assertThat(policy.check("Short-1", email, null)).containsExactly(TOO_SHORT);
        assertThat(policy.check("x".repeat(129), email, null)).containsExactly(TOO_LONG);
        assertThat(policy.check("Q1W2E3R4T5Y6", email, null)).as("case-insensitive").containsExactly(COMMONLY_USED);
        assertThat(policy.check("Ndongo-Is-My-Name-1", email, null)).containsExactly(CONTAINS_ACCOUNT_NAME);
        assertThat(policy.check("Strong-Password-99", email, "Jean Strong")).containsExactly(CONTAINS_ACCOUNT_NAME);
        assertThat(policy.check("Tab\tInside-Password", email, null)).containsExactly(CONTROL_CHARACTER);
        assertThat(policy.check(null, email, null)).containsExactly(TOO_SHORT);
    }

    @Test
    void countsCharactersNotBytes() {
        assertThat(policy.check("ééééééééééé1", email, null)).as("12 characters").isEmpty();
    }

    @Test
    void ignoresNameFragmentsTooShortToMatter() {
        assertThat(policy.check("Correct-Horse-Battery-42", EmailAddress.of("al@demo.invalid"), "Al Bo")).isEmpty();
    }

    @Test
    void normalisesAndValidatesEmailAddresses() {
        assertThat(EmailAddress.of("  Awa.Demo@Example.ORG ").value()).isEqualTo("awa.demo@example.org");
        assertThat(EmailAddress.of("a@b.cm").localPart()).isEqualTo("a");
        for (String bad : new String[] {"", "no-at", "two@@ats.org", "space in@x.org", "a@nodot", "a@x.c",
                "ctrl\u0001@x.org", "x".repeat(250) + "@x.org"}) {
            assertThatThrownBy(() -> EmailAddress.of(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(EmailAddress.of("a@b.cm").toString()).doesNotContain("a@b.cm");
    }

    @Test
    void locksForTheConfiguredDuration() {
        LockoutPolicy lockout = new LockoutPolicy(5, Duration.ofMinutes(15));
        Instant now = Instant.parse("2026-10-21T10:00:00Z");
        assertThat(lockout.lockedUntil(now)).isEqualTo(Instant.parse("2026-10-21T10:15:00Z"));
        assertThatThrownBy(() -> new LockoutPolicy(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockoutPolicy(5, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
