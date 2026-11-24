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
  (add ",demo" to seed customer1/customer2 with FULL KYC and balances)
- Run AML service: ./mvnw -pl risk-aml-service spring-boot:run -Dspring-boot.run.profiles=local
- Run both images instead (ADR 0022): docker compose -f infra/docker-compose.yml --profile apps up -d --build
- Smoke test a running stack: scripts/smoke.sh
- Web app: cd frontend && npm ci && npm run dev (see Frontend)

## Stack
Java 25, Spring Boot 4.x, Spring Security 7 (OAuth2 resource server, Keycloak),
Spring Data JPA + PostgreSQL + Flyway, Spring Modulith, Kafka, Redis, Resilience4j,
Bucket4j, Lombok (entities only), springdoc-openapi (ADR 0010), AWS SDK v2 S3 (ADR 0011), ShedLock (ADR 0013), Spring Mail (ADR 0017), AspectJ via spring-boot-starter-aspectj (ADR 0018), Spring Cache on Redis via spring-boot-starter-cache (ADR 0019; never balances), OpenTelemetry over OTLP via spring-boot-starter-opentelemetry + opentelemetry-logback-appender (ADR 0020), Testcontainers, WireMock (ADR 0014), ArchUnit (ADR 0015),
JUnit, Mockito, AssertJ. CI only: Trivy and gitleaks from digest-pinned images, no third-party actions (ADR 0021).
Web app: see Frontend below (ADR 0023).

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
- Records for DTOs/events, mapped by a static of(...) factory on the target record (no MapStruct, ADR 0016). Entities never leave their module.
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
- ModularityTests (Modulith verify) and ArchitectureRulesTest (ArchUnit, ADR 0015) must stay green.

## Security rules
- No secrets in code or Git. Use .env (git-ignored) and .env.example.
- Only Razorpay TEST keys. Only fake test users and data.
- Every endpoint has an explicit role rule; ownership checks prevent BOLA. The security chain ends in
  denyAll(): a new endpoint without its own rule is refused (add the rule and a SecurityMatrixIT row).

## Frontend (frontend/, ADR 0023)
React 19 + TypeScript (strict) + Vite, React Router, TanStack Query, Tailwind + shadcn/ui,
react-oidc-context (oidc-client-ts); from Day 30: React Hook Form + Zod, Recharts.
Vitest + React Testing Library + MSW, Playwright, ESLint. Node version in frontend/.nvmrc.
- Commands (in frontend/): npm ci | npm run dev (5173, needs the local stack) | npm run lint |
  npm run typecheck | npm test | npm run build | npm run e2e (built app, no backend) |
  npm run e2e:stack (real Keycloak login; needs TRUSTLEDGER_DEV_USER_PASSWORD) | npm run ci
- All API calls go through the API client (src/api): it adds the bearer token, X-Correlation-Id and
  Idempotency-Key, and maps ProblemDetail errorCode to friendly messages. Never call fetch or XMLHttpRequest
  directly (ESLint enforces it; uploads with progress use apiClient.upload). A new backend errorCode needs a message
  in src/api/errors.ts (a test checks it).
- Forms: React Hook Form + Zod schemas that mirror the backend validators (src/features/*/schemas.ts); the server
  stays the authority. Days in India time (src/lib/dates.ts), money via formatMoney/parseRupees (src/lib/money.ts).
- No money arithmetic with floating point: amounts are decimal strings from the API, bigint paise in
  the browser (src/lib/money.ts). No Number()/parseFloat on money.
- Tokens never in localStorage or sessionStorage: oidc-client-ts userStore is in memory only.
- UI role checks (RequireRole, navigation) are convenience; the backend enforces every role and
  ownership rule. A new screen needs its route under the right RequireRole and a nav entry.
- Only fake data; the TEST MODE banner stays on every page. VITE_* values are public: never secrets.

## Workflow
- Plan first, wait for approval, then implement. Stay inside the current milestone.
- Do not add dependencies not listed above without asking and writing an ADR in docs/adr/.
- Finish with: ./mvnw verify passing, a short summary, and what I should learn from it.
- When I say "I will write X myself", write the failing tests and scaffolding only.