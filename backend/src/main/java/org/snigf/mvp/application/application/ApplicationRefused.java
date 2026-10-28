// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.application;

import java.util.List;

/**
 * A refused application request, with its stable code (ADR-011) and, for an incomplete or
 * invalid form, the items concerned ({@code MISSING:statement}, {@code PARCEL_NOT_FOUND} ...).
 */
public class ApplicationRefused extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, INVALID, FORBIDDEN }

    private final Kind kind;
    private final String code;
    private final List<String> items;

    public ApplicationRefused(Kind kind, String code) {
        this(kind, code, List.of());
    }

    public ApplicationRefused(Kind kind, String code, List<String> items) {
        super(code);
        this.kind = kind;
        this.code = code;
        this.items = List.copyOf(items);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public List<String> items() {
        return items;
    }
}
