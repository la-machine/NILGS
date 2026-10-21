// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ADR-021: inside every module, dependencies point inwards.
 * <pre>
 *   infrastructure ──► application ──► domain ──► module API (root package)
 * </pre>
 * The domain is plain Java; the application layer knows ports, not adapters. Module
 * boundaries themselves are checked by {@link ModularityTest}.
 */
class DddLayeringTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.snigf.mvp");

    @Test
    void theDomainIsPlainJava() {
        noClasses().that().resideInAPackage("org.snigf.mvp.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "java.sql..", "javax.sql..", "tools.jackson..",
                        "java.nio.file..", "org.slf4j..")
                .because("the domain holds the rules, not the technology (ADR-021)")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void theDomainKnowsNeitherUseCasesNorAdapters() {
        noClasses().that().resideInAPackage("org.snigf.mvp.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.snigf.mvp.*.application..",
                        "org.snigf.mvp.*.infrastructure..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void useCasesDependOnPortsNotAdapters() {
        noClasses().that().resideInAPackage("org.snigf.mvp.*.application..")
                .should().dependOnClassesThat().resideInAPackage("org.snigf.mvp.*.infrastructure..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void thePublishedApiDoesNotExposeTheModuleInternals() {
        noClasses().that().resideInAPackage("org.snigf.mvp.audit")
                .should().dependOnClassesThat().resideInAnyPackage("org.snigf.mvp.audit.domain..",
                        "org.snigf.mvp.audit.application..", "org.snigf.mvp.audit.infrastructure..")
                .check(CLASSES);
    }
}
