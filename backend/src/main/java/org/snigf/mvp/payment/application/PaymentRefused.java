// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.application;

/** A refused payment operation, with its stable code (ADR-011). */
public class PaymentRefused extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, CONFLICT, FORBIDDEN, UNAVAILABLE, UNAUTHENTIC }

    private final Kind kind;
    private final String code;

    public PaymentRefused(Kind kind, String code) {
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
