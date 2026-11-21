package com.trustledger.core;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CLAUDE.md rules that a compiler doesn't check, enforced on every build (Day 14 B, finding M2). Module boundaries
 * are {@link ModularityTests}' job (Spring Modulith); these are the rules <i>inside</i> a module. Each rule names
 * the CLAUDE.md line it guards.
 * <p>
 * Not checkable here: Lombok placement. Lombok annotations have SOURCE retention and are gone from the bytecode,
 * so the setter rule below checks the <i>effect</i> of {@code @Setter}/{@code @Data} (public setters) instead.
 */
class ArchitectureRulesTest {

    private static final String CORE = "com.trustledger.core";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(CORE);
    }

    // "Layers inside a module: web -> application -> domain; infrastructure implements ports."

    @Test
    void domainDependsOnNoOuterLayer() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..web..", "..infrastructure..")
                .check(production);
    }

    @Test
    void applicationDependsNeitherOnWebNorOnInfrastructure() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..web..", "..infrastructure..")
                .check(production);
    }

    @Test
    void webDoesNotUseInfrastructureDirectly() {
        noClasses().that().resideInAPackage("..web..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .check(production);
    }

    @Test
    void controllersLiveInWeb() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..web..")
                .check(production);
    }

    // "@Transactional only on application services."

    @Test
    void transactionalOnlyInApplication() {
        noClasses().that().resideOutsideOfPackage("..application..")
                .should().beAnnotatedWith(Transactional.class)
                .check(production);
        noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage("..application..")
                .should().beAnnotatedWith(Transactional.class)
                .check(production);
    }

    @Test
    void jakartaTransactionalIsNotUsed() {
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("jakarta.transaction.Transactional")
                .because("Spring's @Transactional is the one with readOnly and propagation")
                .check(production);
    }

    // "Inject Clock; never call Instant.now() directly."

    @Test
    void timeComesOnlyFromTheInjectedClock() {
        noClasses().that().doNotHaveFullyQualifiedName(CORE + ".shared.config.ClockConfig")
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(OffsetDateTime.class, "now")
                .orShould().callMethod(YearMonth.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(Clock.class, "systemUTC")
                .orShould().callMethod(Clock.class, "systemDefaultZone")
                .because("tests control time through the Clock bean (ClockConfig is its only source)")
                .check(production);
    }

    // "Money: Money record, long paise. Never double/float."

    @Test
    void noFloatingPointFields() {
        noFields().that().areDeclaredInClassesThat().haveNameNotMatching(".*Properties(\\$.*)?")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .because("money is long paise; only tuning knobs in *Properties (e.g. a backoff multiplier) may be"
                        + " floating point")
                .check(production);
    }

    // "Records for DTOs/events. Entities never leave their module." / "No @Data/@Setter on entities."

    @Test
    void requestAndResponseTypesAreRecords() {
        classes().that().resideInAPackage("..web..")
                .and(simpleNameEndingWith("Request").or(simpleNameEndingWith("Response")))
                .and(not(assignableTo(HttpServletRequest.class))) // CachedBodyRequest wraps the servlet request
                .should().beRecords()
                .check(production);
    }

    @Test
    void entitiesLiveInDomain() {
        classes().that().areAnnotatedWith(Entity.class)
                .should().resideInAPackage("..domain..")
                .check(production);
    }

    @Test
    void entitiesHaveNoPublicSetters() {
        methods().that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class)
                .and().haveNameStartingWith("set")
                .should().notBePublic()
                .because("state changes go through domain methods such as wallet.freeze(reason)")
                .allowEmptyShould(true)
                .check(production);
    }

    // "Errors: throw TrustLedgerException(ErrorCode); global handler returns ProblemDetail."

    @Test
    void noResponseStatusExceptions() {
        // GlobalExceptionHandler is the one place that maps Spring's own exceptions (some extend
        // ResponseStatusException, e.g. HandlerMethodValidationException); everyone else must not use them.
        noClasses().that().doNotHaveFullyQualifiedName(CORE + ".shared.web.GlobalExceptionHandler")
                .should().dependOnClassesThat().areAssignableTo(ResponseStatusException.class)
                .because("errors are TrustLedgerException(ErrorCode), mapped once by GlobalExceptionHandler")
                .check(production);
    }
}
