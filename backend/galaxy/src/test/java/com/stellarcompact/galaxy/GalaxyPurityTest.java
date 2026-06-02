package com.stellarcompact.galaxy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Build-time guard that the galaxy library stays pure. Mirror of
 * {@code EnginePurityTest}: bans {@code java.io}, {@code java.net} and Spring
 * imports that the maven-enforcer artifact rule cannot detect.
 */
class GalaxyPurityTest {

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.stellarcompact.galaxy");

    @Test
    void galaxyMustNotImportIoOrNetOrSpring() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..",
                        "java.net..",
                        "org.springframework..")
                .because("the galaxy module is a pure, framework-free library: "
                        + "no I/O, no networking, no Spring")
                // A clean module legitimately has zero matching classes; that is
                // a PASS, not an empty-rule error. The rule still fails loudly the
                // moment any production class imports a forbidden package.
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
