// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.infrastructure.web;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.procedure.application.ProcedureProperties;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public notices list is throttled per client address (ADR-042 §3), like public verification. */
class NoticeRateLimiterTest {

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/notices");
        request.setRemoteAddr(address);
        return request;
    }

    @Test
    void anAddressIsRefusedPastItsLimitAndToldWhenToComeBack() {
        NoticeRateLimiter limiter = new NoticeRateLimiter(new ProcedureProperties(Duration.ofDays(30), 2, 100, 50));
        limiter.check(from("192.0.2.10"));
        limiter.check(from("192.0.2.10"));
        assertThatThrownBy(() -> limiter.check(from("192.0.2.10")))
                .isInstanceOfSatisfying(NoticeRateLimiter.Limited.class, e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 60L));
        // Another address has its own bucket.
        limiter.check(from("192.0.2.11"));
    }

    @Test
    void theObjectionPeriodIsBoundedAtStart() {
        assertThatThrownBy(() -> new ProcedureProperties(Duration.ofSeconds(30), 30, 100, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcedureProperties(Duration.ofDays(400), 30, 100, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ProcedureProperties(Duration.ofMinutes(5), 30, 100, 50).objectionPeriod()).isEqualTo(Duration.ofMinutes(5));
    }
}
