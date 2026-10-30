// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.domain;

/**
 * The kinds of document SNIGF issues, each with its current template version. Only types whose
 * legal basis is established may be added; for now the DEMO acknowledgment alone, which
 * establishes no right (ADR-039).
 */
public enum DocumentType {

    /** The application was submitted; states nothing about rights. DEMO, no legal value. */
    DEMO_SUBMISSION_ACKNOWLEDGMENT("ack-demo-1", "SUBMISSION_ACK");

    private final String templateVersion;
    private final String operation;

    DocumentType(String templateVersion, String operation) {
        this.templateVersion = templateVersion;
        this.operation = operation;
    }

    public String templateVersion() {
        return templateVersion;
    }

    /** The operation key of the one issuance of this type for a subject: retries find it. */
    public String operationKey(java.util.UUID subjectId) {
        return operation + ":" + subjectId;
    }
}
