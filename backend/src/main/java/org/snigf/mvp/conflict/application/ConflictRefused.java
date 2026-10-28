// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.application;

/** A refused alert request, with its stable code (ADR-011). */
public class ConflictRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, INVALID, FORBIDDEN }

    private final Kind kind;
    private final String code;

    public ConflictRefused(Kind kind, String code) {
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
