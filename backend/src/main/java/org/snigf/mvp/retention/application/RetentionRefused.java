// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.retention.application;

import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;

/** A retention or legal-hold operation refused, with a precise code the screens can show. */
public class RetentionRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, FORBIDDEN, CONFLICT, INVALID }

    private static final Pattern REASON = Pattern.compile(": ([A-Z][A-Z0-9_]{2,63})\\b");
    private static final Set<String> FORBIDDEN = Set.of("RECORDS_FORBIDDEN", "REQUESTER_CANNOT_DECIDE");
    private static final Set<String> CONFLICT = Set.of("HOLD_RELEASED", "RELEASE_PENDING", "RELEASE_CLOSED");

    private final Kind kind;
    private final String code;

    public RetentionRefused(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    /** The kind of a reason code raised by the database. */
    public static RetentionRefused of(String code) {
        Kind kind = code.endsWith("_NOT_FOUND") ? Kind.NOT_FOUND
                : FORBIDDEN.contains(code) ? Kind.FORBIDDEN
                : CONFLICT.contains(code) ? Kind.CONFLICT
                : Kind.INVALID;
        return new RetentionRefused(kind, code);
    }

    /** RT001 carries the reason as its message; a broken constraint is an invalid request. Anything else is rethrown. */
    static RetentionRefused translated(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if ("RT001".equals(state)) {
                    Matcher reason = REASON.matcher(String.valueOf(sql.getMessage()));
                    return of(reason.find() ? reason.group(1) : "RECORDS_REFUSED");
                }
                if (state.startsWith("23") || state.startsWith("22")) {
                    return new RetentionRefused(Kind.INVALID, "RECORDS_INVALID");
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
