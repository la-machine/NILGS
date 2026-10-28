// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.web;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.snigf.mvp.payment.application.CallbackService;
import org.snigf.mvp.payment.application.PaymentProperties;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.shared.config.SnigfProperties;
import org.snigf.mvp.shared.security.PublicEndpoint;
import org.snigf.mvp.shared.web.ClientCertificateFilter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Callbacks of the DEMO payment simulator (ADR-037). No session and no CSRF token: the caller is
 * a provider, not a browser. It reaches this endpoint only through the proxy's provider listener,
 * which requires its client certificate and writes the verified subject in
 * {@code SNIGF_PROXY_PROVIDER_CLIENT_HEADER}; that header is believed only on the proxy's own
 * certificate (the browser listener removes it and refuses this path). The provider adapter then
 * checks the timestamp and the HMAC before reading the body.
 *
 * <p>Every authenticated callback is answered 200 {@code {"received": true}}, duplicates and
 * exceptions included, so the provider stops retrying; what the platform made of it is recorded
 * and audited, not told to the provider.
 */
@RestController
@RequestMapping("/api/v1/payments/callbacks")
class CallbackController {

    private static final List<String> SIGNED_HEADERS = List.of("X-Snigf-Timestamp", "X-Snigf-Signature");

    private final CallbackService callbacks;
    private final String identityHeader;
    private final boolean transportVerified;

    CallbackController(CallbackService callbacks, PaymentProperties payment, SnigfProperties snigf) {
        this.callbacks = callbacks;
        this.identityHeader = payment.providerIdentityHeader();
        this.transportVerified = snigf.security().transport().requireClientCertificate();
    }

    record ReceivedResponse(boolean received) {
    }

    @PostMapping(path = "/demo-simulator", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PublicEndpoint("payment provider callback: provider certificate verified by the proxy, timestamp and HMAC by the adapter (ADR-037)")
    ReceivedResponse demoSimulator(HttpServletRequest request, @RequestBody byte[] body) {
        Map<String, String> headers = new HashMap<>();
        for (String name : SIGNED_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name.toLowerCase(Locale.ROOT), value);
            }
        }
        callbacks.receive(new PaymentProvider.Callback(identity(request), headers, body));
        return new ReceivedResponse(true);
    }

    /** The provider's subject, only as the proxy wrote it (or anywhere in the test profile, which has no transport). */
    private String identity(HttpServletRequest request) {
        boolean fromProxy = request.getAttribute(ClientCertificateFilter.VERIFIED_PROXY_ATTRIBUTE) != null;
        return fromProxy || !transportVerified ? request.getHeader(identityHeader) : null;
    }
}
