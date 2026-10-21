// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** The browser address recorded in audit events cannot be chosen by the browser. */
class ClientAddressTest {

    private static final String HEADER = "X-Snigf-Client-Address";

    @Test
    void believesTheProxyHeaderOnlyAfterTheProxyCertificateWasAdmitted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.20.0.5");
        request.addHeader(HEADER, "203.0.113.7");

        assertThat(ClientAddress.of(request)).as("not admitted: TCP peer").contains("172.20.0.5");

        request.setAttribute(ClientCertificateFilter.VERIFIED_PROXY_ATTRIBUTE, HEADER);
        assertThat(ClientAddress.of(request)).as("admitted proxy").contains("203.0.113.7");
    }

    @Test
    void discardsAnythingThatIsNotAnIpLiteral() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ClientCertificateFilter.VERIFIED_PROXY_ATTRIBUTE, HEADER);
        for (String value : new String[] {"evil.example", "1.2.3.4, 5.6.7.8", "1.2.3.4'--", "", "x".repeat(60)}) {
            request.removeHeader(HEADER);
            request.addHeader(HEADER, value);
            assertThat(ClientAddress.of(request)).as(value).isEmpty();
        }
    }

    @Test
    void acceptsIpv6() {
        assertThat(ClientAddress.literal("2001:db8::1")).isPresent();
    }
}
