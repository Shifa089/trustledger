# ADR 0001: Modular monolith for the core, separate AML service

- Status: Accepted
- Date: 2026-11-10
- Spec: docs/SPEC.md §3 "Architecture and repository structure"

## Context

TrustLedger is a regulated digital wallet. Its core use case, moving money between
wallets, must be atomic: debit, credit and the double-entry journal either all happen or
none do. At the same time the domain has clearly separate areas (identity, KYC, wallet,
ledger, payments, audit, privacy, notification, reporting) that should not leak into each
other, and anti-money-laundering (AML) screening scales and changes on its own schedule.

The team is one developer, with one deployment target and a limited budget.

## Decision

1. Build `core-app` as a **modular monolith** with Spring Modulith. Each business area
   is an application module (a top-level package under `com.trustledger.core`):
   `shared` (open), `identity`, `kyc`, `wallet`, `ledger`, `payments`, `audit`,
   `privacy`, `notification`, `reporting`.
2. A module's **public API** is only the types in its base package (`*Api` interface,
   event records, view records). Sub-packages (`web`, `application`, `domain`,
   `infrastructure`) are internal. Inside a module the layers are
   web -> application -> domain, with infrastructure adapters implementing ports.
3. Build **`risk-aml-service`** as a separate Spring Boot application with its own schema,
   integrated only through Kafka events (contracts in `event-contracts`).

## Rules (enforced by tests in CI)

1. A module talks to another only through its base-package API or by listening to its
   events, never through its repositories or entities.
2. `ledger` depends on no other business module; others call `LedgerApi.post(...)`.
3. Money movement is synchronous and atomic in one database transaction
   (payments -> wallet + ledger). Everything else (notifications, audit, AML) reacts to events.
4. Controllers call application services only. JPA entities never leave their module;
   APIs return records.
5. No foreign keys across module schemas; modules reference each other by ID.
6. No remote calls (Razorpay, S3, Kafka) inside a database transaction that holds row locks.
7. `ApplicationModules.of(TrustLedgerApplication.class).verify()` (`ModularityTests`) and
   ArchUnit layer tests run on every build.

## Alternatives considered

- **Microservices for every area.** Rejected: a transfer would need a distributed
  transaction or a saga across wallet, ledger and payments, adding failure modes
  (partial postings, compensation logic) exactly where a wallet can least afford them,
  plus the operational cost of many deployments for one developer.
- **Plain layered monolith (controller/service/repository packages).** Rejected: nothing
  stops `payments` from reaching into `wallet`'s repository, so boundaries erode and the
  code can never be split later.

## Consequences

- Positive: real ACID transactions for money; one deployable, one database, simple local
  setup; boundaries are verified by `ModularityTests` instead of by discipline; any module
  can later be extracted into a service along the same boundary (its API and events
  become the remote contract).
- Positive: the AML service demonstrates event-driven integration (outbox -> Kafka ->
  idempotent consumer, freeze request back) and scales independently.
- Negative: the core scales as one unit; a hot module cannot be scaled alone.
- Negative: module rules need tooling (Spring Modulith, ArchUnit) and discipline in code
  review; cross-module reads go through APIs, which can mean extra queries.
- Negative: one shared PostgreSQL database is a single point of failure for the core
  (acceptable for this project; production would use a managed, multi-AZ setup).
