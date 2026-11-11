// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.application;

import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;

/** A register operation refused, with a precise code the screens can show. */
public class RegisterRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, FORBIDDEN, CONFLICT, INVALID, UNAVAILABLE, TOO_LARGE, BROKEN, RATE_LIMITED }

    private static final Pattern REASON = Pattern.compile(": ([A-Z][A-Z0-9_]{2,63})\\b");
    private static final Set<String> FORBIDDEN = Set.of("REGISTER_FORBIDDEN", "SELF_CONFIRMATION", "ACTOR_HAS_INTEREST", "NOT_THE_PROPOSER",
            "RECEIVER_CANNOT_DECIDE", "SIGNATURE_NOT_OWN_KEY", "REQUESTER_CANNOT_DECIDE", "NOT_THE_REQUESTER");
    private static final Set<String> CONFLICT = Set.of("TITLE_CHANGED", "TITLE_CLOSED", "TITLE_LOCKED", "OPEN_CONFLICT", "BLOCKING_RESTRICTION",
            "TRANSACTION_NOT_HOLDING", "TRANSACTION_CLOSED", "INSCRIPTION_CLOSED", "NOT_PENDING_CONFIRMATION", "CONFLICT_CLOSED",
            "DUAL_CONTROL_REQUIRED", "DUAL_CONTROL_NOT_APPLICABLE", "HOLDING_NOT_IN_FORCE", "ARRIVING_NOT_IN_FORCE", "OTHER_CONFLICTS_OPEN",
            "TARGET_CANCELLED", "TARGET_NOT_ACTIVE", "REQUEST_IN_PROGRESS", "DOCUMENT_ALREADY_REPLACED", "DEPOSIT_DOCUMENT_LIMIT",
            "DUPLICATE_DEPOSIT", "REPLACEMENT_PENDING", "REPLACEMENT_CLOSED", "REPLACEMENT_APPROVAL_REQUIRED");

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

    /** RG001 carries the reason as its message; a broken constraint is an invalid request. Anything else is rethrown. */
    static RegisterRefused translated(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if ("RG001".equals(state)) {
                    Matcher reason = REASON.matcher(String.valueOf(sql.getMessage()));
                    return of(reason.find() ? reason.group(1) : "REGISTER_REFUSED");
                }
                if (state.startsWith("23") || state.startsWith("22")) {
                    return new RegisterRefused(Kind.INVALID, "REGISTER_INVALID");
                }
                if ("40001".equals(state) || "40P01".equals(state)) {
                    return new RegisterRefused(Kind.CONFLICT, "TITLE_CHANGED");
                }
            }
        }
        throw e;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
