// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.application;

/** A refused issued-document operation, with its stable code (ADR-011). */
public class IssuanceRefused extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, CONFLICT, FORBIDDEN, UNAVAILABLE, TOO_LARGE, BROKEN }

    private final Kind kind;
    private final String code;

    public IssuanceRefused(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
