// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.application;

import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;

/** A procedure operation refused, with a precise code the screens can show (ADR-011). */
public class ProcedureRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, FORBIDDEN, CONFLICT, INVALID }

    private static final Pattern REASON = Pattern.compile(": ([A-Z][A-Z0-9_]{2,63})\\b");
    private static final Set<String> NOT_FOUND = Set.of("CASE_NOT_FOUND", "STAGE_UNKNOWN", "FILE_NOT_FOUND", "OBJECTION_NOT_FOUND",
            "PERSON_NOT_FOUND");
    private static final Set<String> FORBIDDEN = Set.of("PROCEDURE_FORBIDDEN", "APPLICANT_CANNOT_RECORD", "ASSIGNEE_CANNOT_RECORD",
            "VISIT_RECORDER_CANNOT_REPORT", "OBJECTION_RECORDER_CANNOT_DECIDE", "APPLICANT_CANNOT_DECIDE", "ASSIGNEE_CANNOT_DECIDE",
            "STAGE_RECORDER_CANNOT_DECIDE", "PARTY_FORBIDDEN", "RECORDER_CANNOT_CHECK", "RECORDER_CANNOT_AUTHORISE",
            "APPLICANT_CANNOT_REOPEN", "DECISION_AUTHORITY_CANNOT_REOPEN", "ASSIGNEE_CANNOT_REOPEN", "STAGE_RECORDER_CANNOT_REOPEN",
            "OBJECTION_DECIDER_CANNOT_REOPEN", "ACTOR_HAS_INTEREST");
    private static final Set<String> CONFLICT = Set.of("STAGE_NOT_IN_PROCEDURE", "STAGE_NOT_YET_AVAILABLE", "CASE_NOT_UNDER_REVIEW",
            "STAGE_ALREADY_RECORDED", "STALE_RECORD", "NOTHING_TO_CORRECT", "FIELD_VISIT_REQUIRED", "OPEN_ALERT", "FILE_INTEGRITY_FAILED",
            "EARLIER_STAGES_INCOMPLETE", "NOTICE_NOT_PUBLISHED", "CASE_CLOSED", "OBJECTION_ALREADY_DECIDED", "IDEMPOTENCY_KEY_REUSED",
            "CASE_REGISTERED", "STALE_VERSION", "ALREADY_CHECKED", "NOT_YET_RECORDED", "CASE_NOT_OPEN", "PARTIES_NOT_APPLICABLE",
            "PARTIES_INCOMPLETE", "EXCEPTION_NOT_APPLICABLE", "ALREADY_AUTHORISED", "CASE_NOT_APPROVED", "CREATION_IN_PROGRESS",
            "GROUNDS_MISSING", "RECONSIDERATION_NOT_APPLICABLE",
            // Procedure B's stages (V37, ADR-042 step 6): what the register and the case's deposit say now.
            "TRANSFERORS_NOT_HOLDERS", "TRANSFEROR_NOT_CHECKED", "CASE_TITLE_MISSING", "DEPOSIT_NOT_HOLDING", "TITLE_CLOSED",
            "BLOCKING_RESTRICTION", "OPEN_CONFLICT");

    private final Kind kind;
    private final String code;

    public ProcedureRefused(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    /** The kind of a reason code raised by the database. */
    public static ProcedureRefused of(String code) {
        Kind kind = NOT_FOUND.contains(code) ? Kind.NOT_FOUND
                : FORBIDDEN.contains(code) ? Kind.FORBIDDEN
                : CONFLICT.contains(code) ? Kind.CONFLICT
                : Kind.INVALID;
        return new ProcedureRefused(kind, code);
    }

    /** PC001 carries the reason as its message; a broken constraint is an invalid request. Anything else is rethrown. */
    static ProcedureRefused translated(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if ("PC001".equals(state)) {
                    Matcher reason = REASON.matcher(String.valueOf(sql.getMessage()));
                    return of(reason.find() ? reason.group(1) : "PROCEDURE_REFUSED");
                }
                if (state.startsWith("23") || state.startsWith("22")) {
                    return new ProcedureRefused(Kind.INVALID, "PROCEDURE_INVALID");
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
