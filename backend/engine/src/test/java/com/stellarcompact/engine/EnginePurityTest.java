package com.stellarcompact.engine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Build-time guard that the engine stays pure. The maven-enforcer rule bans
 * Spring <em>artifacts</em>; this test bans the forbidden <em>imports</em> that
 * the enforcer cannot see: {@code java.io}, {@code java.net} and Spring.
 */
class EnginePurityTest {

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.stellarcompact.engine");

    @Test
    void engineMustNotImportIoOrNetOrSpring() {
        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..",
                        "java.net..",
                        "org.springframework..")
                .because("the engine module is pure and framework-free: "
                        + "no I/O, no networking, no Spring")
                // A clean module legitimately has zero matching classes; that is
                // a PASS, not an empty-rule error. The rule still fails loudly the
                // moment any production class imports a forbidden package.
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
