// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.util.UUID;

/** Port: ends every server-side session of an account at once (ADR-004). */
public interface SessionRevoker {

    /** Returns the number of sessions ended. */
    int revokeAll(UUID userId);
}
