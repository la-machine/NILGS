// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.shared.security.Permission;

import static org.assertj.core.api.Assertions.assertThat;

/** A type's procedure (ADR-042 §1): known stages of its own procedure, each once, in rank order, with what they need. */
class ProcedureTest {

    private static Requirements.Procedure a(String... stages) {
        return new Requirements.Procedure("A", List.of(stages), "CREATION", true);
    }

    @Test
    void theTwoDemoProceduresAreValid() {
        assertThat(a("CADASTRAL_REVIEW", "FIELD_VISIT", "COMMISSION_REPORT", "PUBLICATION").problems()).isEmpty();
        assertThat(new Requirements.Procedure("B", List.of("TITLE_VERIFICATION", "DUTIES_EVIDENCE"), "TRANSFER", true).problems())
                .isEmpty();
    }

    @Test
    void unknownStagesStagesOfAnotherProcedureRepeatsAndDisorderAreRefused() {
        assertThat(a("CADASTRAL_REVIEW", "SURVEY").problems()).containsExactly("procedure: STAGE_UNKNOWN:SURVEY");
        assertThat(a("CADASTRAL_REVIEW", "TITLE_VERIFICATION").problems())
                .containsExactly("procedure: STAGE_OF_ANOTHER_PROCEDURE:TITLE_VERIFICATION");
        assertThat(a("FIELD_VISIT", "FIELD_VISIT").problems()).containsExactly("procedure: STAGE_TWICE:FIELD_VISIT");
        assertThat(a("PUBLICATION", "CADASTRAL_REVIEW").problems()).containsExactly("procedure: STAGE_OUT_OF_ORDER:CADASTRAL_REVIEW");
        assertThat(a().problems()).containsExactly("procedure: PROCEDURE_NO_STAGES");
    }

    @Test
    void theCommissionsReportNeedsTheFieldVisitBeforeIt() {
        assertThat(a("CADASTRAL_REVIEW", "COMMISSION_REPORT").problems())
                .containsExactly("procedure: STAGE_NEEDS:COMMISSION_REPORT<FIELD_VISIT");
    }

    @Test
    void eachProcedureRegistersItsOwnActAndNoOther() {
        assertThat(new Requirements.Procedure("A", List.of("CADASTRAL_REVIEW"), "TRANSFER", true).problems())
                .containsExactly("procedure: PROCEDURE_REGISTERS_MISMATCH (A registers CREATION, B registers TRANSFER)");
        assertThat(new Requirements.Procedure("C", List.of("CADASTRAL_REVIEW"), "CREATION", true).problems())
                .contains("procedure: PROCEDURE_UNKNOWN (A or B)");
    }

    @Test
    void theCommissionsReportHasItsOwnPermissionTheOnlyDelegableOne() {
        assertThat(ProcedureStage.COMMISSION_REPORT.permission()).isEqualTo(Permission.PROCEDURE_COMMISSION_REPORT)
                .isNotEqualTo(ProcedureStage.FIELD_VISIT.permission());
        assertThat(Permission.PROCEDURE_COMMISSION_REPORT.delegable()).isTrue();
        assertThat(List.of(Permission.PROCEDURE_STAGE_CADASTRAL, Permission.PROCEDURE_STAGE_PUBLISH, Permission.PROCEDURE_STAGE_TITLE,
                Permission.PROCEDURE_OBJECTION_RECORD, Permission.PROCEDURE_OBJECTION_DECIDE, Permission.PROCEDURE_DELIVERY_RECORD))
                .noneMatch(Permission::delegable);
    }

    @Test
    void aTypeWithoutAProcedureIsUnchanged() {
        assertThat(new Requirements(List.of(), List.of()).procedure()).isNull();
    }
}
