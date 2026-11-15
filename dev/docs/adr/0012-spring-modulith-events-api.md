# ADR 0012: spring-modulith-events-api for `@ApplicationModuleListener`

- Status: Accepted
- Date: 2026-11-15
- Spec: docs/SPEC.md §2 FR-W-1, §8 M4 ("wallet created on KycLevelUpgraded")

## Context

FR-W-1 opens (or upgrades) a wallet when the kyc module publishes `KycLevelUpgraded`. Spring Modulith's
`@ApplicationModuleListener` is the standard way for one module to react to another module's event: it is
`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` + `@Async` in one annotation,
and Modulith's tests (`Scenario`, the documenter) recognise it.

The annotation lives in `org.springframework.modulith:spring-modulith-events-api`. `spring-modulith-starter-core`,
which the project already uses, does not bring it in, so the build failed with "cannot find symbol".
CLAUDE.md lists "Spring Modulith" in the stack but requires an ADR for every artifact not already on the
classpath. The user asked for this ADR on Day 6.

## Options

1. **Add `spring-modulith-events-api`** (version from the Spring Modulith BOM, 2.1.2). Annotations and
   event interfaces only, no runtime behaviour, no auto-configuration.
2. **Compose the annotations by hand** (`@TransactionalEventListener` + `@Transactional(REQUIRES_NEW)`):
   works, but repeats three annotations on every cross-module listener and hides the intent from
   Modulith's tooling.
3. **Add `spring-modulith-starter-jpa` now**: brings the event publication registry (outbox) as well, but
   that is planned for M8 together with its table, retries and monitoring. Out of the current milestone.

## Decision

Option 1. `spring-modulith-events-api`, no explicit version (managed by the existing
`spring-modulith-bom`, `spring-modulith.version` in the parent POM).

## Consequences

- No new vendor or release train: it ships with Spring Modulith, already in the stack and the BOM.
- Without `@EnableAsync` the listener runs on the publishing thread right after the KYC commit, in its own
  transaction. A failure there is logged and the event is lost (the KYC approval stays committed). A later
  `KycLevelUpgraded(FULL)` still opens the wallet. M8 adds the publication registry
  (`spring-modulith-starter-jpa`) so incomplete events are stored and retried; that will need its own entry
  but not a new ADR, since it is the same project and BOM.
- Every future cross-module listener (ledger, notification, audit) uses the same annotation.
