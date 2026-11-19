// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.application.application.ApplicationTypeCatalogue;
import org.snigf.mvp.application.domain.ApplicationRepository;
import org.snigf.mvp.application.domain.ApplicationType;
import org.snigf.mvp.application.domain.ProcedureStage;
import org.snigf.mvp.application.domain.Requirements;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Slice 1 design C4: demonstration types with configurable synthetic document categories, labelled as
 * demonstration requirements; from Slice 4 (ADR-042), the two procedures' types and their stages. The
 * catalogue comes from configuration; a change is a new version (the old one is kept), audited; an
 * invalid catalogue is refused whole, and an invalid procedure is refused by the database too.
 */
class ApplicationTypeConfigIT extends IdentityIntegrationTest {

    @Autowired
    ApplicationTypeCatalogue catalogue;

    @Autowired
    ApplicationRepository applications;

    @Test
    void theDemoTypeIsSeededSaysDemoAndHasThreeDemoDocumentCategories() {
        ApplicationType demo = applications.latestType("DEMO_REGISTRATION").orElseThrow();
        assertThat(demo.available()).isTrue();
        assertThat(demo.nameFr()).isEqualTo("Demande d'immatriculation (DEMO)");
        assertThat(demo.nameEn()).contains("(DEMO)");
        assertThat(demo.requirements().documents()).hasSize(3)
                .allSatisfy(d -> assertThat(d.code()).startsWith("DEMO_"))
                .allSatisfy(d -> assertThat(d.labelEn()).contains("(DEMO)"));
    }

    @Test
    void aChangedDefinitionIsANewVersionAuditedAndADraftMovesToItWhenSaved() throws Exception {
        ApplicationType before = applications.latestType("DEMO_REGISTRATION").orElseThrow();
        String email = email();
        Browser applicant = new Browser();
        applicant.register(email, "Demo Applicant", PASSWORD);
        applicant.login(email, PASSWORD);
        String id = json.readTree(sendJson(applicant, post("/api/v1/applications").header("Idempotency-Key", "it-" + UUID.randomUUID()),
                "{\"typeCode\":\"DEMO_REGISTRATION\",\"data\":{}}").getContentAsString()).path("id").asString("");
        try {
            List<Requirements.DocumentCategory> documents = new ArrayList<>(before.requirements().documents());
            documents.add(new Requirements.DocumentCategory("DEMO_TAX_RECEIPT", false, "Quittance (DEMO)", "Tax receipt (DEMO)"));
            ApplicationType.Definition changed = new ApplicationType.Definition(before.code(), before.nameFr(), before.nameEn(),
                    new Requirements(before.requirements().fields(), documents));
            assertThat(SystemActor.call("application-types", () -> catalogue.apply(withEveryOtherType(changed)))).isEqualTo(1);
            assertThat(SystemActor.call("application-types", () -> catalogue.apply(withEveryOtherType(changed))))
                    .as("the same definition again writes nothing").isZero();

            ApplicationType after = applications.latestType("DEMO_REGISTRATION").orElseThrow();
            assertThat(after.versionNo()).isEqualTo(before.versionNo() + 1);
            assertThat(applications.type(before.id())).as("the old version is kept").isPresent();
            assertThat(jdbc.queryForObject("SELECT (details->>'versionNo')::int FROM audit_event"
                    + " WHERE action = 'APPLICATION_TYPE_CHANGED' ORDER BY chain_pos DESC LIMIT 1", Integer.class))
                    .isEqualTo(after.versionNo());

            JsonNode draft = json.readTree(applicant.send(get("/api/v1/applications/" + id)).getContentAsString());
            assertThat(draft.path("type").path("versionNo").asInt()).as("a draft keeps its version").isEqualTo(before.versionNo());
            JsonNode saved = json.readTree(sendJson(applicant, put("/api/v1/applications/" + id),
                    json.writeValueAsString(Map.of("expectedVersion", 0, "data", Map.of()))).getContentAsString());
            assertThat(saved.path("type").path("versionNo").asInt()).as("... until it is saved").isEqualTo(after.versionNo());
            assertThat(saved.path("type").path("requirements").path("documents").size()).isEqualTo(4);
        } finally {
            SystemActor.call("application-types", () -> catalogue.apply(withEveryOtherType(new ApplicationType.Definition(
                    before.code(), before.nameFr(), before.nameEn(), before.requirements()))));
        }
    }

    /** The catalogue as configured, with one definition changed: a type left out would be made unavailable. */
    private List<ApplicationType.Definition> withEveryOtherType(ApplicationType.Definition changed) {
        List<ApplicationType.Definition> all = new ArrayList<>();
        for (String code : applications.typeCodes()) {
            ApplicationType t = applications.latestType(code).orElseThrow();
            all.add(code.equals(changed.code()) ? changed : new ApplicationType.Definition(code, t.nameFr(), t.nameEn(), t.requirements()));
        }
        return all;
    }

    @Test
    void theTwoProceduresTypesCarryTheirStagesAndTheGenericTypeNone() {
        Requirements.Procedure a = applications.latestType("DEMO_IMMATRICULATION").orElseThrow().requirements().procedure();
        assertThat(a.code()).isEqualTo("A");
        assertThat(a.stages()).containsExactly("CADASTRAL_REVIEW", "FIELD_VISIT", "COMMISSION_REPORT", "PUBLICATION");
        assertThat(a.registers()).isEqualTo("CREATION");
        assertThat(a.delivery()).isTrue();
        Requirements.Procedure b = applications.latestType("DEMO_MUTATION").orElseThrow().requirements().procedure();
        assertThat(b.stages()).containsExactly("TITLE_VERIFICATION", "DUTIES_EVIDENCE");
        assertThat(b.registers()).isEqualTo("TRANSFER");
        assertThat(applications.latestType("DEMO_REGISTRATION").orElseThrow().requirements().procedure())
                .as("the generic case flow keeps no procedure").isNull();
        assertThat(applications.latestType("DEMO_MUTATION").orElseThrow().nameEn()).contains("DEMO");
    }

    @Test
    void theStageCatalogueInTheCodeIsTheDatabasesOne() {
        List<String> database = jdbc.queryForList("SELECT code || ':' || procedure || ':' || rank || ':' || coalesce(requires, '-')"
                + " || ':' || permission FROM procedure_stage ORDER BY procedure, rank", String.class);
        List<String> code = java.util.Arrays.stream(ProcedureStage.values())
                .map(st -> st.name() + ":" + st.procedure() + ":" + st.rank() + ":"
                        + (st.requires() == null ? "-" : st.requires().name()) + ":" + st.permission().code())
                .sorted(java.util.Comparator.comparing((String x) -> x.split(":")[1]).thenComparingInt(x -> Integer.parseInt(x.split(":")[2])))
                .toList();
        assertThat(database).isEqualTo(code);
    }

    @Test
    void anInvalidProcedureIsRefusedByTheCodeAndByTheDatabase() {
        ApplicationType demo = applications.latestType("DEMO_IMMATRICULATION").orElseThrow();
        Requirements wrong = new Requirements(demo.requirements().fields(), demo.requirements().documents(),
                new Requirements.Procedure("A", List.of("PUBLICATION", "COMMISSION_REPORT"), "CREATION", true));
        assertThatThrownBy(() -> SystemActor.call("application-types", () -> catalogue.apply(withEveryOtherType(
                new ApplicationType.Definition(demo.code(), demo.nameFr(), demo.nameEn(), wrong)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STAGE_OUT_OF_ORDER:COMMISSION_REPORT")
                .hasMessageContaining("STAGE_NEEDS:COMMISSION_REPORT<FIELD_VISIT");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO application_type (code, version_no, available, name_fr, name_en, requirements)"
                + " VALUES ('DEMO_PROCEDURE_PROBE', 1, true, 'Sonde (DEMO)', 'Probe (DEMO)', CAST(? AS jsonb))",
                "{\"fields\":[],\"documents\":[],\"procedure\":{\"code\":\"B\",\"stages\":[\"CADASTRAL_REVIEW\"],"
                        + "\"registers\":\"CREATION\",\"delivery\":true}}"))
                .hasMessageContaining("STAGE_OF_ANOTHER_PROCEDURE:CADASTRAL_REVIEW")
                .hasMessageContaining("PROCEDURE_REGISTERS_MISMATCH");
        assertThat(applications.latestType("DEMO_PROCEDURE_PROBE")).isEmpty();
    }

    @Test
    void anInvalidCatalogueIsRefusedWholeWithEveryReason() {
        ApplicationType demo = applications.latestType("DEMO_REGISTRATION").orElseThrow();
        int versions = demo.versionNo();
        ApplicationType.Definition real = new ApplicationType.Definition("DEMO_REGISTRATION", "Demande d'immatriculation",
                demo.nameEn(), demo.requirements());
        ApplicationType.Definition twice = new ApplicationType.Definition("DEMO_OTHER", "Autre (DEMO)", "Other (DEMO)",
                demo.requirements());
        assertThatThrownBy(() -> SystemActor.call("application-types", () -> catalogue.apply(List.of(real, twice, twice))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saying DEMO in both languages")
                .hasMessageContaining("defined twice");
        assertThat(applications.latestType("DEMO_REGISTRATION").orElseThrow().versionNo()).isEqualTo(versions);
        assertThat(applications.latestType("DEMO_OTHER")).isEmpty();
    }
}
