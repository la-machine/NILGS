// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creating accounts for another module (ADR-041): a professional account, with the same address,
 * name, language and password rules (P7) as an applicant's, the password peppered and hashed here.
 * It grants nothing: a professional's rights come from the professional module's lifecycle.
 */
public interface AccountRegistration {

    record Account(String email, String displayName, String password, String preferredLanguage) {

        @Override
        public String toString() {
            return "Account[<redacted>]";
        }
    }

    /** A rule was broken; nothing was stored. */
    class Rejected extends RuntimeException {
        private final List<String> codes;
        private final boolean onlyPassword;

        public Rejected(List<String> codes, boolean onlyPassword) {
            super("account rejected: " + codes);
            this.codes = List.copyOf(codes);
            this.onlyPassword = onlyPassword;
        }

        public List<String> codes() {
            return codes;
        }

        public boolean onlyPassword() {
            return onlyPassword;
        }
    }

    /**
     * Creates a {@code PROFESSIONAL} account in the caller's transaction. Empty when the address is
     * already registered: the caller answers the same either way, so nothing reveals an account.
     */
    Optional<UUID> registerProfessional(Account account);
}
