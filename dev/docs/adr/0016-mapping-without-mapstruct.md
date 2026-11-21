# ADR 0016: Mapping with static factory methods on records, not MapStruct

- Status: Accepted
- Date: 2026-11-21
- Spec: docs/SPEC.md §6 "DTOs and mapping" (changed by this ADR); CLAUDE.md Stack (changed by this ADR)

## Context

SPEC §6 said "MapStruct for mapping", and CLAUDE.md listed MapStruct in the stack. In practice, M1–M7 never
added it. Every mapping (19 of them) is a static factory on the target record: `TopupResponse.of(TopupResult)`,
`HeldTransferView.of(Transfer)`, and so on. The Day 14 Session B review flagged this as a convention violation
(finding L1). There are two ways to resolve it: add MapStruct, or change the convention to what the code does.

What our mappings actually do:
- **money** becomes a decimal **string** (`Money.toDecimalString()`) and a currency code;
- **personal data** is **masked** on the way out (`Masking.phone(...)`, `Masking.pan(...)`), which depends on
  the caller (the owner sees more than an officer);
- a few values come from outside the source object (for example the gateway's public `keyId`);
- everything else is a plain field copy.

## Options

1. **Add MapStruct** and rewrite the 19 factories as mapper interfaces. The field copies get shorter. But money
   formatting and masking would turn into `@Mapping(expression = "java(...)")` strings or `@Named` helper
   methods, which the compiler only checks after code generation. And it adds an annotation processor next to
   Lombok's, which have to be ordered correctly.
2. **Keep static factories on the records** and change the written convention. Each mapping is plain Java,
   next to the type it builds, checked by the compiler, and visible in a debugger. Masking is impossible to miss,
   because it is right there in the factory.
3. **Hand-written mapper classes** (`TopupMapper`). Same code as option 2, but in a separate class, with no
   benefit at this size.

## Decision

Option 2. Requests, responses, views and events are records. Mapping is a static `of(...)` / `from(...)` factory
on the **target** record, written by hand. **MapStruct is removed** from the CLAUDE.md stack and SPEC §6.

Rules that stay:
- entities never leave their module;
- money goes out as a decimal string, never a float;
- personal data is masked in the factory;
- records for requests and responses (enforced by `ArchitectureRulesTest`, ADR 0015).

## Consequences

- No annotation processor and no generated code to read when a mapping is wrong.
- A big DTO means more lines of plain field copying. If a future module (for example reporting, M11) needs many
  large, purely structural mappings, revisit this with an ADR.
- Mapping factories are tested where they matter: masking and money formatting, in the controller and view tests.
