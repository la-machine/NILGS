// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.web;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision D6, ADR-027 and ADR-038: office-scoped reading of the audit trail exists only where
 * the database filters each event by the offices of its subjects, recorded at the time of the
 * event: the event search and the timeline, at {@code audit.event.search} somewhere. Every other
 * audit endpoint (verification, checkpoints, keys) requires its permission everywhere. A new
 * office-scoped audit endpoint fails here until it is added on purpose, with its filter.
 */
class AuditReadingScopeTest {

    private static final Set<String> OFFICE_SCOPED = Set.of("events", "timeline");

    @Test
    void onlyTheFilteredReadsAcceptAnOfficeScopedPermission() {
        Method[] endpoints = Arrays.stream(AuditController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PreAuthorize.class)).toArray(Method[]::new);
        assertThat(endpoints).isNotEmpty();
        for (Method endpoint : endpoints) {
            String rule = endpoint.getAnnotation(PreAuthorize.class).value();
            if (OFFICE_SCOPED.contains(endpoint.getName())) {
                assertThat(rule).as(endpoint.getName())
                        .isEqualTo("@access.canSomewhere(authentication, 'audit.event.search')");
            } else {
                assertThat(rule).as(endpoint.getName() + " (ADR-027: needs its permission everywhere)")
                        .startsWith("@access.can(authentication, ").doesNotContain("canAt");
            }
        }
        assertThat(Arrays.stream(endpoints).map(Method::getName)).containsAll(OFFICE_SCOPED);
    }
}
