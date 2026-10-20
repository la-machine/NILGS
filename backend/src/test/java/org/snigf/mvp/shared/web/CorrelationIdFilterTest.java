// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.web;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesAnIdIgnoringTheClientValueAndClearsTheLoggingContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.addHeader(CorrelationIdFilter.HEADER, "client-chosen-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        String header = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(header).isNotEqualTo("client-chosen-value");
        assertThat(UUID.fromString(header)).isEqualTo(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));
        assertThat(seenInMdc.get()).isEqualTo(header);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
