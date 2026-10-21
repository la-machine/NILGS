// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

/**
 * Turns passwords into stored hashes and checks them. The implementation peppers the
 * password with a key the database never sees, then applies Argon2id, so neither a
 * database dump nor the key alone allows an offline guess.
 */
public interface PasswordHasher {

    String hash(String password);

    boolean matches(String password, String storedHash);

    /** True when the hash uses weaker parameters or an older pepper version than current. */
    boolean needsRehash(String storedHash);

    /**
     * Spends the same time as {@link #matches} against a hash that cannot match, so an
     * unknown e-mail address cannot be told apart from a wrong password by timing.
     */
    void spendEquivalentTime(String password);

    /** Too many hashes are being computed at once; the caller should retry later (HTTP 503). */
    class Busy extends RuntimeException {
        public Busy() {
            super("password hashing capacity exhausted");
        }
    }
}
