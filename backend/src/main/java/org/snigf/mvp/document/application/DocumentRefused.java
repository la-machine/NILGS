// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.application;

/** A refused document request, with its stable code (ADR-011). */
public class DocumentRefused extends RuntimeException {

    /** BROKEN: the stored bytes no longer match their hash (never served). */
    public enum Kind { NOT_FOUND, CONFLICT, INVALID, TOO_LARGE, BROKEN }

    private final Kind kind;
    private final String code;

    public DocumentRefused(Kind kind, String code) {
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
