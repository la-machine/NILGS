// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.application;

import java.util.UUID;

import org.snigf.mvp.audit.domain.Actor;

/**
 * Port: the facts a caller must never supply about an event (Slice 1 design §7): who
 * acted, under which correlation id, from which address.
 */
public interface AuditRequestContext {

    /** The acting user, service or anonymous caller; refuses an unattributable principal. */
    Actor actor();

    UUID correlationId();

    /** The browser's address, or null outside a request. */
    String sourceIp();
}
