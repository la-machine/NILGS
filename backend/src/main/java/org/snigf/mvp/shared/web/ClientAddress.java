// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.web;

import java.net.InetAddress;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address of the browser that made a request, for audit records.
 *
 * <p>Behind the reverse proxy, the TCP peer is always the proxy. The proxy writes the
 * browser's address in its own header, overwriting any value the browser sent. That
 * header is believed only when {@link ClientCertificateFilter} has admitted the request
 * on the proxy's client certificate; otherwise the TCP peer address is used. Values that
 * are not an IP literal are discarded (no DNS lookup is ever made).
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    public static Optional<String> of(HttpServletRequest request) {
        if (request.getAttribute(ClientCertificateFilter.VERIFIED_PROXY_ATTRIBUTE) instanceof String header) {
            return literal(request.getHeader(header));
        }
        return literal(request.getRemoteAddr());
    }

    static Optional<String> literal(String value) {
        if (value == null || value.isBlank() || value.length() > 45) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(value.strip()).getHostAddress());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
