// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.web;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.Set;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.shared.config.SnigfProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Admits only callers whose TLS client certificate carries an allowed common name
 * (ADR-017). The TLS layer has already verified that the certificate chains to the SNIGF
 * CA; this filter narrows that to the reverse proxy's identity, so another service's
 * certificate from the same CA (for example a database client certificate) is refused.
 *
 * <p>A refused request gets 403 with the stable code {@value #ERROR_CODE}, and nothing
 * else of the application runs. An admitted request is marked with
 * {@link #VERIFIED_PROXY_ATTRIBUTE}, so the proxy's client-address header is believed
 * from the proxy only ({@link ClientAddress}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ClientCertificateFilter extends OncePerRequestFilter {

    public static final String ERROR_CODE = "TRANSPORT_CLIENT_REJECTED";
    /** Request attribute set on admission: the name of the header carrying the browser's address. */
    public static final String VERIFIED_PROXY_ATTRIBUTE = ClientCertificateFilter.class.getName() + ".proxy";
    static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private static final Logger log = LoggerFactory.getLogger(ClientCertificateFilter.class);

    private final boolean required;
    private final Set<String> allowedCommonNames;
    private final String clientAddressHeader;

    public ClientCertificateFilter(SnigfProperties properties) {
        SnigfProperties.Transport transport = properties.security().transport();
        this.required = transport.requireClientCertificate();
        this.allowedCommonNames = Set.copyOf(transport.allowedClientCommonNames());
        this.clientAddressHeader = transport.clientAddressHeader();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!required) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> commonName = clientCommonName(request);
        if (commonName.isPresent() && allowedCommonNames.contains(commonName.get())) {
            request.setAttribute(VERIFIED_PROXY_ATTRIBUTE, clientAddressHeader);
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rejected transport client: certificate CN={}", commonName.orElse("<none>"));
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\""
                + ERROR_CODE + "\"}");
    }

    static Optional<String> clientCommonName(HttpServletRequest request) {
        if (!(request.getAttribute(CERTIFICATE_ATTRIBUTE) instanceof X509Certificate[] chain) || chain.length == 0) {
            return Optional.empty();
        }
        try {
            LdapName subject = new LdapName(chain[0].getSubjectX500Principal().getName());
            return subject.getRdns().stream()
                    .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType()))
                    .map(Rdn::getValue)
                    .map(Object::toString)
                    .reduce((first, second) -> second);   // most specific CN
        } catch (InvalidNameException e) {
            return Optional.empty();
        }
    }
}
