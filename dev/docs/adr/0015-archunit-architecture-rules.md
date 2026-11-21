# ADR 0015: ArchUnit for the rules inside a module

- Status: Accepted
- Date: 2026-11-21
- Spec: docs/SPEC.md §3 (item 7, "ArchUnit layer tests run in CI"), §9 (Architecture tests); CLAUDE.md Testing rules

## Context

CLAUDE.md says "ModularityTests (Modulith verify) and ArchUnit rules must stay green", but until Day 14 no
ArchUnit rule existed. The Day 14 Session B review found that, and also that several change logs had said "ArchUnit
green". Spring Modulith's `verify()` only checks the boundaries **between** modules. The rules **inside** a module
were followed (the review checked them by hand), but nothing stopped a regression:

- layers: web → application → domain, and infrastructure implements ports;
- `@Transactional` only on application services;
- time only from the injected `Clock`;
- no `double`/`float` near money;
- records for requests and responses;
- entities only in `domain`, changed through domain methods (no public setters);
- errors as `TrustLedgerException`, not `ResponseStatusException`.

ArchUnit is not in the CLAUDE.md stack list, so it needs an ADR.

## Options

1. **ArchUnit, declared directly.** It's already on the classpath: `spring-modulith-core` depends on
   `com.tngtech.archunit:archunit` 1.4.2 with **runtime** scope, because Modulith uses it to build its module model.
2. **ArchUnit, used only through the transitive dependency.** Works today, but it's invisible in our `pom.xml`. If
   Modulith ever dropped or shaded it, our tests would stop compiling for a reason nobody could see.
3. **Rules as grep in CI or a Checkstyle setup.** Text matching can't tell a call from a comment and can't follow
   types (for example "assignable to `HttpServletRequest`"). It's also one more tool to configure.
4. **Code review only.** That's what we had. It's how the missing rules went unnoticed.

## Decision

Option 1: `com.tngtech.archunit:archunit` **1.4.2**, declared in `core-app/pom.xml` with **`runtime` scope**, the
same scope and version Modulith brings. It's not `test` scope, because a direct declaration overrides the
transitive one: `test` would take ArchUnit off the runtime classpath that Modulith needs. Maven puts runtime
dependencies on the test classpath too, so the tests compile against it.

Plain ArchUnit (`ClassFileImporter` + `rule.check(classes)` in JUnit 5 tests), not `archunit-junit5`. The
plain API is enough, and it avoids a second artifact.

Rules live in `core-app/src/test/java/com/trustledger/core/ArchitectureRulesTest.java`. Each one names the
CLAUDE.md line it guards. When it was added, a deliberately planted violation (an `@Transactional` method calling
`Instant.now()` in a web class) failed exactly the two matching rules.

## Consequences

- An architecture regression now fails `./mvnw verify`, like a broken unit test.
- **Lombok placement can't be checked:** Lombok annotations have SOURCE retention and aren't in the bytecode.
  The rule checks the effect instead: entities have no public setters.
- Exceptions to a rule are written in the rule, with the reason: `ClockConfig` may create the system clock,
  `*Properties` records may hold a floating-point tuning knob, and `GlobalExceptionHandler` may handle Spring's own
  `ResponseStatusException` subclasses.
- When Spring Modulith is upgraded, check the ArchUnit version it brings
  (`./mvnw -pl core-app dependency:tree -Dincludes=com.tngtech.archunit`) and keep ours equal.
