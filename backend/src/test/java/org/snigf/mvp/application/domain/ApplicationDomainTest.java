// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The application's own rules (MVP-02, C4), before the database checks them again (V13). */
class ApplicationDomainTest {

    static final Requirements DEMO = new Requirements(List.of(
            new Requirements.Field("capacity", Requirements.Kind.CHOICE, true, 0,
                    List.of(new Requirements.Option("OWNER", "Propriétaire", "Owner"),
                            new Requirements.Option("HEIR", "Héritier", "Heir")),
                    "Qualité", "Capacity", null, null),
            new Requirements.Field("representativeName", Requirements.Kind.TEXT, false, 10, null,
                    "Représentant", "Representative", null, null),
            new Requirements.Field("statement", Requirements.Kind.LONG_TEXT, true, 2000, null,
                    "Exposé", "Statement", "Aide", "Help")),
            List.of(new Requirements.DocumentCategory("DEMO_IDENTITY", true, "Identité (DEMO)", "Identity (DEMO)")));

    @Test
    void referencesAreWellFormedWithTheirCheckCharacterAndNeverAcceptATypo() {
        ApplicationReference issued = ApplicationReference.issue(2026, 42);
        assertThat(issued.value()).matches("^DEMO-APP-2026-000042-[0-9A-Z]$");
        assertThat(ApplicationReference.isValid(issued.value())).isTrue();
        String typo = issued.value().replace("000042", "000024");
        assertThat(ApplicationReference.isValid(typo)).as("adjacent digits swapped").isFalse();
        assertThat(ApplicationReference.isValid("DEMO-APP-2026-42-X")).isFalse();
        assertThatThrownBy(() -> ApplicationReference.issue(2026, 1_000_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheDrawnTransitionsAreLegalAndTheEndsAreFinal() {
        assertThat(ApplicationState.DRAFT.canMoveTo(ApplicationState.SUBMITTED)).isTrue();
        assertThat(ApplicationState.DRAFT.canMoveTo(ApplicationState.WITHDRAWN)).as("a draft is deleted, not withdrawn").isFalse();
        assertThat(ApplicationState.SUBMITTED.canMoveTo(ApplicationState.APPROVED)).isFalse();
        assertThat(ApplicationState.PENDING_DECISION.canMoveTo(ApplicationState.UNDER_REVIEW)).as("returned to review").isTrue();
        assertThat(ApplicationState.APPROVED.isFinal()).isTrue();
        assertThat(ApplicationState.WITHDRAWN.isFinal()).isTrue();
        assertThat(ApplicationState.INFO_REQUESTED.isFinal()).isFalse();
    }

    @Test
    void aDraftMayBeIncompleteButNeverWrong() {
        assertThat(DEMO.check(Map.of(), false)).isEmpty();
        assertThat(DEMO.check(Map.of("capacity", "KING"), false)).containsExactly("NOT_AN_OPTION:capacity");
        assertThat(DEMO.check(Map.of("representativeName", "A very long name"), false)).containsExactly("TOO_LONG:representativeName");
        assertThat(DEMO.check(Map.of("salary", "1"), false)).containsExactly("UNKNOWN_FIELD:salary");
    }

    @Test
    void aSubmissionNamesEveryRequiredFieldLeftEmpty() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("representativeName", "Ama");
        assertThat(DEMO.check(form, true)).containsExactly("MISSING:capacity", "MISSING:statement");
        form.put("capacity", "HEIR");
        form.put("statement", "Inherited in 2019 (synthetic)");
        assertThat(DEMO.check(form, true)).isEmpty();
    }

    @Test
    void aDefinitionIsCheckedBeforeItIsUsed() {
        assertThat(DEMO.problems()).isEmpty();
        Requirements broken = new Requirements(List.of(
                new Requirements.Field("Bad Key", Requirements.Kind.TEXT, true, 0, null, "", "x", "only fr", null),
                new Requirements.Field("choice", Requirements.Kind.CHOICE, true, 0,
                        List.of(new Requirements.Option("ONLY", "Seul", "Only")), "Choix", "Choice", null, null)),
                List.of(new Requirements.DocumentCategory("IDENTITY", true, "Identité", "Identity")));
        assertThat(String.join("\n", broken.problems()))
                .contains("key must be camelCase")
                .contains("maxLength must be 1 to")
                .contains("labels in French and English are required")
                .contains("give both languages or neither")
                .contains("a choice has 2 to 30 options")
                .contains("starting with DEMO_");
        assertThat(new ApplicationType.Definition("DEMO_X1", "Demande réelle", "Real", DEMO).problems())
                .anyMatch(p -> p.contains("saying DEMO in both languages"));
    }
}
