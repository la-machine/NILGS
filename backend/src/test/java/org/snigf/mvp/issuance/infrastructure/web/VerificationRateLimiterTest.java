// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.infrastructure.web;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.issuance.application.IssuanceProperties;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public verification is throttled per client address (ADR-039); one address never spends another's. */
class VerificationRateLimiterTest {

    @Test
    void anAddressGetsItsAttemptsPerMinuteThenWaits() {
        VerificationRateLimiter limiter = new VerificationRateLimiter(
                new IssuanceProperties(URI.create("https://localhost:47000"), 2_097_152, 3, 100, 50));
        MockHttpServletRequest one = new MockHttpServletRequest();
        one.setRemoteAddr("192.0.2.10");
        MockHttpServletRequest two = new MockHttpServletRequest();
        two.setRemoteAddr("192.0.2.11");
        for (int i = 0; i < 3; i++) {
            limiter.check(one);
        }
        assertThatThrownBy(() -> limiter.check(one)).isInstanceOf(VerificationRateLimiter.Limited.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((VerificationRateLimiter.Limited) e).retryAfterSeconds())
                        .isPositive());
        assertThatCode(() -> limiter.check(two)).doesNotThrowAnyException();
    }

    @Test
    void theVerificationAddressMustBeHttps() {
        assertThatThrownBy(() -> new IssuanceProperties(URI.create("http://localhost:47000"), 2_097_152, 3, 100, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
