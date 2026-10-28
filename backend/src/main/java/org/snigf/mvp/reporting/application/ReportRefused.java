// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.application;

/** A refused report request, with its stable code (ADR-011). */
public class ReportRefused extends RuntimeException {

    public enum Kind { INVALID }

    private final Kind kind;
    private final String code;

    public ReportRefused(Kind kind, String code) {
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
