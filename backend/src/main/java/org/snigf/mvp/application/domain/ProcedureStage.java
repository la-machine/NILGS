// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import org.snigf.mvp.shared.security.Permission;

/**
 * The stage catalogue of the procedures (ADR-042 §1, D23 and D26), the same as {@code procedure_stage} (V31):
 * each stage belongs to one procedure, has a rank within it, may need an earlier stage, and names the permission
 * of whoever records it. A type version lists some of them, in rank order. DEMO: none is a validated legal step.
 */
public enum ProcedureStage {
    CADASTRAL_REVIEW("A", 10, null, Permission.PROCEDURE_STAGE_CADASTRAL),
    FIELD_VISIT("A", 20, null, Permission.PROCEDURE_STAGE_CADASTRAL),
    /** Recorded and validated by the commission's secretary or a designated member, never by the visit's officer. */
    COMMISSION_REPORT("A", 30, FIELD_VISIT, Permission.PROCEDURE_COMMISSION_REPORT),
    PUBLICATION("A", 40, null, Permission.PROCEDURE_STAGE_PUBLISH),
    TITLE_VERIFICATION("B", 10, null, Permission.PROCEDURE_STAGE_TITLE),
    DUTIES_EVIDENCE("B", 20, null, Permission.PROCEDURE_STAGE_TITLE);

    private final String procedure;
    private final int rank;
    private final ProcedureStage requires;
    private final Permission permission;

    ProcedureStage(String procedure, int rank, ProcedureStage requires, Permission permission) {
        this.procedure = procedure;
        this.rank = rank;
        this.requires = requires;
        this.permission = permission;
    }

    public String procedure() {
        return procedure;
    }

    public int rank() {
        return rank;
    }

    /** The stage that must come before this one in a type's list, or null. */
    public ProcedureStage requires() {
        return requires;
    }

    public Permission permission() {
        return permission;
    }

    /** The register act a procedure leads to once its case is approved: A creates a title, B transfers one. */
    public static String registers(String procedure) {
        return switch (procedure) {
            case "A" -> "CREATION";
            case "B" -> "TRANSFER";
            default -> null;
        };
    }

    /** The stage of this code, or null. */
    public static ProcedureStage of(String code) {
        for (ProcedureStage s : values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        return null;
    }
}
