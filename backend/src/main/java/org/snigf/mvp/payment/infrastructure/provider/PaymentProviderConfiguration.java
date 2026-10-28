// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.provider;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Set;

import javax.net.ssl.SSLParameters;

import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.payment.application.PaymentProperties;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.shared.config.DeploymentEnvironment;
import org.snigf.mvp.shared.config.SnigfProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chooses the payment provider (ADR-037). Only {@code DEMO_SIMULATOR} exists: a real provider
 * stays disabled until an ADR approves it and adds its adapter here. The in-process simulator
 * channel is refused outside the test profile, and the HTTPS channel needs its URL and SSL bundle.
 */
@Configuration(proxyBeanMethods = false)
class PaymentProviderConfiguration {

    @Bean
    SimulatorChannel simulatorChannel(PaymentProperties payment, SnigfProperties snigf, SslBundles sslBundles) {
        PaymentProperties.Simulator simulator = payment.simulator();
        return switch (simulator.channel()) {
            case IN_PROCESS -> {
                if (snigf.environment() != DeploymentEnvironment.TEST) {
                    throw new IllegalStateException("snigf.payment.simulator.channel=IN_PROCESS is for the test profile only");
                }
                yield new InProcessSimulatorChannel();
            }
            case HTTPS -> https(simulator, sslBundles);
        };
    }

    @Bean
    PaymentProvider paymentProvider(PaymentProperties payment, SimulatorChannel channel, KeyService keys, Clock clock) {
        return switch (payment.provider()) {
            case DEMO_SIMULATOR -> new DemoSimulatorProvider(channel, keys, Set.copyOf(payment.providerClientNames()),
                    payment.callbackMaxSkew(), clock);
        };
    }

    private static HttpsSimulatorChannel https(PaymentProperties.Simulator settings, SslBundles sslBundles) {
        if (settings.url() == null || !"https".equals(settings.url().getScheme()) || settings.sslBundle() == null
                || settings.connectTimeout() == null || settings.requestTimeout() == null) {
            throw new IllegalStateException("snigf.payment.simulator needs an https url, an ssl-bundle and timeouts");
        }
        // Mutual TLS 1.3: the bundle holds the application's provider-client certificate and the
        // CA the simulator's certificate must chain to; host names are verified.
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
        RestClient rest = RestClient.builder()
                .baseUrl(settings.url().toString().replaceAll("/+$", "") + "/")
                .requestFactory(requestFactory)
                .build();
        return new HttpsSimulatorChannel(rest);
    }
}
