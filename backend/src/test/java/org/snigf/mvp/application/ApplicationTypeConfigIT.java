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
 * Slice 1 design C4: one demonstration type, "Demande d'immatriculation (DEMO)", with three
 * configurable synthetic document categories, labelled as demonstration requirements. The
 * catalogue comes from configuration; a change is a new version (the old one is kept), audited;
 * an invalid catalogue is refused whole.
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
            assertThat(SystemActor.call("application-types", () -> catalogue.apply(List.of(changed)))).isEqualTo(1);
            assertThat(SystemActor.call("application-types", () -> catalogue.apply(List.of(changed))))
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
            SystemActor.call("application-types", () -> catalogue.apply(List.of(new ApplicationType.Definition(
                    before.code(), before.nameFr(), before.nameEn(), before.requirements()))));
        }
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
