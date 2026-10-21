// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.snigf.mvp.shared.security.PublicEndpoint;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 1 design §6: every endpoint states who may call it. A controller method without
 * {@code @PreAuthorize} or {@code @PublicEndpoint} fails the build, so nothing becomes
 * public by omission. Controllers live in the infrastructure layer (ADR-021).
 */
class EndpointAnnotationArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.snigf.mvp");

    @Test
    void everyEndpointDeclaresItsAccessRule() {
        methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().areAnnotatedWith(GetMapping.class)
                .or().areAnnotatedWith(PostMapping.class)
                .or().areAnnotatedWith(PutMapping.class)
                .or().areAnnotatedWith(PatchMapping.class)
                .or().areAnnotatedWith(DeleteMapping.class)
                .or().areAnnotatedWith(RequestMapping.class)
                .should().beAnnotatedWith(PreAuthorize.class).orShould().beAnnotatedWith(PublicEndpoint.class)
                .because("an endpoint must never be public by omission")
                .check(CLASSES);
    }

    @Test
    void controllersAreAdaptersInTheInfrastructureLayer() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().resideOutsideOfPackage("org.snigf.mvp.*.infrastructure..")
                .check(CLASSES);
    }
}
