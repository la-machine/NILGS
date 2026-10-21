// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.snigf.mvp.audit.AuditedPrincipal;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.audit.application.AuditRequestContext;
import org.snigf.mvp.audit.domain.Actor;
import org.snigf.mvp.shared.web.ClientAddress;
import org.snigf.mvp.shared.web.CorrelationIdFilter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the actor from {@link SystemActor} (scheduled work) or the Spring Security
 * context, and the correlation id and address from the current request. An authenticated
 * principal that cannot be attributed is refused, never recorded as anonymous.
 */
@Component
public class SecurityAuditRequestContext implements AuditRequestContext {

    @Override
    public Actor actor() {
        Optional<String> service = SystemActor.current();
        if (service.isPresent()) {
            return Actor.service(service.get());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Actor.anonymous();
        }
        if (auth.getPrincipal() instanceof AuditedPrincipal principal && principal.userId() != null) {
            return Actor.user(principal.userId(), List.copyOf(principal.auditRoles()));
        }
        throw new IllegalStateException("authenticated principal "
                + auth.getPrincipal().getClass().getName() + " does not implement AuditedPrincipal");
    }

    /** The request's server-generated id, else the task's (MDC), else a new one. */
    @Override
    public UUID correlationId() {
        Optional<HttpServletRequest> request = request();
        if (request.isPresent()
                && request.get().getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) instanceof UUID id) {
            return id;
        }
        String mdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (mdc != null) {
            try {
                return UUID.fromString(mdc);
            } catch (IllegalArgumentException e) {
                // not one of ours: a fresh id below
            }
        }
        return UUID.randomUUID();
    }

    @Override
    public String sourceIp() {
        return request().flatMap(ClientAddress::of).orElse(null);
    }

    private static Optional<HttpServletRequest> request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? Optional.of(attributes.getRequest())
                : Optional.empty();
    }
}
