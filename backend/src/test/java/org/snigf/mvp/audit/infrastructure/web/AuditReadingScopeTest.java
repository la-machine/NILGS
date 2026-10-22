// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.web;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision D6 and ADR-027: office-scoped reading of the audit trail stays unavailable until
 * audit events carry an office recorded at the time of the event. Every audit endpoint
 * therefore requires its permission everywhere ({@code @access.can}); a supervisor's
 * {@code audit.event.search@OFFICE} opens nothing. Switching an endpoint to
 * {@code canAt} is the implementation of ADR-027 and must come with it.
 */
class AuditReadingScopeTest {

    @Test
    void noAuditEndpointAcceptsAnOfficeScopedPermissionYet() {
        Method[] endpoints = Arrays.stream(AuditController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PreAuthorize.class)).toArray(Method[]::new);
        assertThat(endpoints).isNotEmpty();
        for (Method endpoint : endpoints) {
            assertThat(endpoint.getAnnotation(PreAuthorize.class).value())
                    .as(endpoint.getName() + " (ADR-027: no office-scoped audit reading before events carry an office)")
                    .startsWith("@access.can(authentication, ")
                    .doesNotContain("canAt");
        }
    }
}
