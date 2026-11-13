# TrustLedger

Regulated digital wallet (fintech portfolio project). Modular monolith `core-app`
(Spring Boot 4, Spring Modulith) + `risk-aml-service` (Spring Boot 4) over Kafka.
Full spec: docs/SPEC.md. Read the relevant section before every task.

## Commands
- Start infra: docker compose -f infra/docker-compose.yml up -d
- Start with observability: add --profile obs
- Build and test everything: ./mvnw verify
- Integration tests only: ./mvnw verify -DskipUnitTests
- Once before -pl runs (and after changing event-contracts): ./mvnw install -DskipTests
- Run core app: ./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local
- Run AML service: ./mvnw -pl risk-aml-service spring-boot:run -Dspring-boot.run.profiles=local

## Stack
Java 25, Spring Boot 4.x, Spring Security 7 (OAuth2 resource server, Keycloak),
Spring Data JPA + PostgreSQL + Flyway, Spring Modulith, Kafka, Redis, Resilience4j,
Bucket4j, MapStruct, Lombok (entities only), springdoc-openapi (ADR 0010), Testcontainers,
JUnit, Mockito, AssertJ.

## Architecture rules (never break)
- Modules: shared, identity, kyc, wallet, ledger, payments, audit, privacy,
  notification, reporting. Public API = types in the module base package only.
- Cross-module calls only via the module's *Api interface or its events.
- Layers inside a module: web -> application -> domain; infrastructure implements ports.
- ledger depends on no other business module.
- Money movement = one DB transaction. Lock wallets with SELECT FOR UPDATE in
  ascending ID order. No remote calls while holding locks.
- journal_entries, journal_lines, audit_events are append-only.
- No foreign keys across module schemas.

## Coding conventions
- Money: Money record, long paise. Never double/float. API amounts are decimal strings.
- Records for DTOs/events. Entities never leave their module.
- No @Data/@Setter on entities; state changes via domain methods.
- Errors: throw TrustLedgerException(ErrorCode); global handler returns ProblemDetail.
- @Transactional only on application services.
- Inject Clock; never call Instant.now() directly.
- Config via @ConfigurationProperties records; no hard-coded limits.
- Never log personal data (PAN, DOB, phone, email, address). Use masking helpers.
- Flyway: V{n}__{module}_{description}.sql. ddl-auto=validate.

## Testing rules
- Every feature ships with tests. Domain logic: unit tests. Persistence, Kafka,
  security: Testcontainers integration tests (no H2).
- Money paths need: happy path, each rejection rule, idempotent retry, concurrency.
- ModularityTests (Modulith verify) and ArchUnit rules must stay green.

## Security rules
- No secrets in code or Git. Use .env (git-ignored) and .env.example.
- Only Razorpay TEST keys. Only fake test users and data.
- Every endpoint has an explicit role rule; ownership checks prevent BOLA.

## Workflow
- Plan first, wait for approval, then implement. Stay inside the current milestone.
- Do not add dependencies not listed above without asking and writing an ADR in docs/adr/.
- Finish with: ./mvnw verify passing, a short summary, and what I should learn from it.
- When I say "I will write X myself", write the failing tests and scaffolding only.