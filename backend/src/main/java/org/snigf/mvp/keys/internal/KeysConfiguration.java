// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.net.http.HttpClient;
import java.time.Clock;

import javax.net.ssl.SSLParameters;

import org.snigf.mvp.shared.config.SnigfProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chooses the key provider (ADR-018). EnvironmentGuard has already refused the in-memory
 * provider outside the test profile, and an incomplete OpenBao configuration.
 */
@Configuration(proxyBeanMethods = false)
class KeysConfiguration {

    @Bean
    CustodyKeyService keyService(SnigfProperties properties, SslBundles sslBundles) {
        SnigfProperties.Keys keys = properties.keys();
        return switch (keys.provider()) {
            case EPHEMERAL -> new EphemeralKeyService(keys.names());
            case OPENBAO -> openBao(keys.openbao(), keys.names(), sslBundles);
        };
    }

    private static OpenBaoKeyService openBao(SnigfProperties.OpenBao settings, SnigfProperties.KeyNames names,
                                             SslBundles sslBundles) {
        if (settings == null) {
            throw new IllegalStateException("snigf.keys.openbao must be configured with the OPENBAO provider");
        }
        // Mutual TLS 1.3: the bundle holds the application's client certificate and the CA
        // that OpenBao's server certificate must chain to; host names are verified.
        SslBundle bundle = sslBundles.getBundle(settings.sslBundle());
        SSLParameters tls = new SSLParameters();
        tls.setProtocols(bundle.getOptions().getEnabledProtocols());
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        HttpClient http = HttpClient.newBuilder()
                .sslContext(bundle.createSslContext())
                .sslParameters(tls)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(settings.requestTimeout());
        // Spring Framework's own builder: JSON through Jackson, no Boot customizers that
        // could add interceptors or change TLS settings.
        RestClient rest = RestClient.builder()
                .baseUrl(settings.url().toString().replaceAll("/+$", "") + "/v1/")
                .requestFactory(requestFactory)
                .build();
        OpenBaoClient client = new OpenBaoClient(rest, settings.authMount(), settings.role(), Clock.systemUTC());
        return new OpenBaoKeyService(client, settings.transitMount(), names);
    }
}
