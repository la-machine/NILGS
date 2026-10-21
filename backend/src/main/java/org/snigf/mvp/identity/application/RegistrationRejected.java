// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.util.List;

/**
 * A registration that breaks a rule. {@code codes} are stable values the interface
 * translates, for example {@code EMAIL_INVALID} or a password policy violation
 * ({@code TOO_SHORT}, {@code COMMONLY_USED}, ...).
 */
public class RegistrationRejected extends RuntimeException {

    private final List<String> codes;

    public RegistrationRejected(List<String> codes) {
        super("registration rejected: " + codes);
        this.codes = List.copyOf(codes);
    }

    public List<String> codes() {
        return codes;
    }

    /** True when every reason concerns the password. */
    public boolean onlyPassword() {
        return codes.stream().allMatch(c -> !c.startsWith("EMAIL_") && !c.startsWith("DISPLAY_NAME_")
                && !c.startsWith("LANGUAGE_"));
    }
}
