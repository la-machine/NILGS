// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/**
 * 403 for a refused call. When the caller is an authenticated user, the refusal is audited
 * as {@code ACCESS_DENIED} in its own transaction (Slice 1 design §6). Anonymous refusals
 * (mostly a missing CSRF token before login) are not audited: anyone on the internet could
 * otherwise fill the serialised audit chain at will. They are logged and rate-limited.
 */
@Component
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private final AuditRecorder audit;

    public AuditingAccessDeniedHandler(AuditRecorder audit) {
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        boolean csrf = denied instanceof CsrfException;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser) {
            audit.recordIndependently(AuditEvent.of(AuditAction.ACCESS_DENIED, AuditOutcome.DENIED)
                    .withDetails(Map.of(
                            "method", request.getMethod(),
                            "path", truncate(request.getRequestURI()),
                            "rule", csrf ? "CSRF" : "AUTHORIZATION")));
        }
        ProblemResponses.write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
                csrf ? "CSRF_INVALID" : "ACCESS_DENIED");
    }

    private static String truncate(String path) {
        return path.length() <= 200 ? path : path.substring(0, 200);
    }
}
