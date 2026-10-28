// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.application;

/**
 * A refused parcel request, with its stable code (ADR-011) and, for geometry refusals,
 * PostGIS's reason (for example the ST_IsValidReason text, MVP-01).
 */
public class ParcelRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, INVALID }

    private final Kind kind;
    private final String code;
    private final String detail;

    public ParcelRefused(Kind kind, String code) {
        this(kind, code, null);
    }

    public ParcelRefused(Kind kind, String code, String detail) {
        super(code);
        this.kind = kind;
        this.code = code;
        this.detail = detail;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    /** A precise reason safe to show, or null. */
    public String detail() {
        return detail;
    }
}
