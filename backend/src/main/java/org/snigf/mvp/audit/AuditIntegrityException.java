// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

/** The audit chain failed verification; the operation that needed a sound chain was refused. */
public class AuditIntegrityException extends RuntimeException {

    private final transient ChainVerification verification;

    public AuditIntegrityException(String message, ChainVerification verification) {
        super(message);
        this.verification = verification;
    }

    public ChainVerification verification() {
        return verification;
    }
}
