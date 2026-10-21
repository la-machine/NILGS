// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.web;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.shared.config.DeploymentEnvironment;
import org.snigf.mvp.shared.config.SnigfProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR-017: only the reverse proxy's certificate identity may call the API. */
class ClientCertificateFilterTest {

    private static ClientCertificateFilter filter(boolean required, String... allowed) {
        return new ClientCertificateFilter(new SnigfProperties(DeploymentEnvironment.DEV, true,
                new SnigfProperties.Security(new SnigfProperties.Transport(required, List.of(allowed), "X-Snigf-Client-Address")),
                new SnigfProperties.Keys(SnigfProperties.KeyProviderType.EPHEMERAL, null, null)));
    }

    private static X509Certificate certificate(String subject) {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
        return cert;
    }

    private static boolean passes(ClientCertificateFilter filter, X509Certificate... chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        if (chain.length > 0) {
            request.setAttribute(ClientCertificateFilter.CERTIFICATE_ATTRIBUTE, chain);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        if (!reached.get()) {
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains(ClientCertificateFilter.ERROR_CODE);
        }
        return reached.get();
    }

    @Test
    void admitsTheProxyIdentity() throws Exception {
        assertThat(passes(filter(true, "snigf-web"), certificate("CN=snigf-web,O=FLYT"))).isTrue();
    }

    @Test
    void refusesOtherCertificatesFromTheSameCa() throws Exception {
        assertThat(passes(filter(true, "snigf-web"), certificate("CN=snigf_migrator,O=FLYT"))).isFalse();
        assertThat(passes(filter(true, "snigf-web"), certificate("CN=snigf-web-evil,O=FLYT"))).isFalse();
    }

    @Test
    void refusesRequestsWithoutACertificate() throws Exception {
        assertThat(passes(filter(true, "snigf-web"))).isFalse();
    }

    @Test
    void usesTheMostSpecificCommonName() throws Exception {
        // X.500 names list the most specific RDN first; a CN smuggled into an outer RDN must not count.
        assertThat(passes(filter(true, "snigf-web"), certificate("CN=intruder,OU=x,CN=snigf-web"))).isFalse();
    }

    @Test
    void isInactiveOnlyWhenExplicitlyNotRequired() throws Exception {
        assertThat(passes(filter(false))).isTrue();
    }
}
