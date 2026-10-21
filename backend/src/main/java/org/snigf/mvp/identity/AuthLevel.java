// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

/**
 * How far a session has been authenticated. Each level is also a granted authority
 * ({@link #authority()}), so URL and method rules can require it.
 */
public enum AuthLevel {
    /**
     * Password verified, second factor still due (staff only): the session may reach the
     * second-factor endpoints, its own session view and logout, nothing else.
     */
    PASSWORD,
    /** Fully authenticated: password, plus the second factor for staff. */
    FULL;

    public String authority() {
        return "AUTH_LEVEL_" + name();
    }
}
