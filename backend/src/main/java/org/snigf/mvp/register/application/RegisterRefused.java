// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.util.Set;

/** A register operation refused, with a precise code the screens can show. */
public class RegisterRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, FORBIDDEN, CONFLICT, INVALID, UNAVAILABLE, TOO_LARGE, BROKEN }

    private static final Set<String> FORBIDDEN = Set.of("REGISTER_FORBIDDEN", "SELF_CONFIRMATION", "ACTOR_HAS_INTEREST", "NOT_THE_PROPOSER",
            "RECEIVER_CANNOT_DECIDE", "SIGNATURE_NOT_OWN_KEY");
    private static final Set<String> CONFLICT = Set.of("TITLE_CHANGED", "TITLE_CLOSED", "TITLE_LOCKED", "OPEN_CONFLICT", "BLOCKING_RESTRICTION",
            "TRANSACTION_NOT_HOLDING", "TRANSACTION_CLOSED", "INSCRIPTION_CLOSED", "NOT_PENDING_CONFIRMATION", "CONFLICT_CLOSED",
            "DUAL_CONTROL_REQUIRED", "DUAL_CONTROL_NOT_APPLICABLE", "HOLDING_NOT_IN_FORCE", "ARRIVING_NOT_IN_FORCE", "OTHER_CONFLICTS_OPEN",
            "TARGET_CANCELLED", "TARGET_NOT_ACTIVE", "REQUEST_IN_PROGRESS", "DOCUMENT_ALREADY_REPLACED", "DEPOSIT_DOCUMENT_LIMIT");

    private final Kind kind;
    private final String code;

    public RegisterRefused(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    /** The kind of a reason code raised by the database. */
    public static RegisterRefused of(String code) {
        Kind kind = code.endsWith("_NOT_FOUND") ? Kind.NOT_FOUND
                : FORBIDDEN.contains(code) ? Kind.FORBIDDEN
                : CONFLICT.contains(code) ? Kind.CONFLICT
                : Kind.INVALID;
        return new RegisterRefused(kind, code);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
