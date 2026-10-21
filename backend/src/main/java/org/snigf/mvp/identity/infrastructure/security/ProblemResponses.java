// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

/**
 * Minimal RFC 9457 bodies written by security filters, before any controller runs. They
 * carry only a status and a stable code (ADR-011): never a message, path or exception.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, int status, String title, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\"}");
    }
}
