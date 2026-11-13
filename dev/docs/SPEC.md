# TrustLedger: Implementation Guide (Claude Code Edition)

Nov 7, 2026 · @MohammadSamiulla Bilagi

## How to build this with Claude Code

Build TrustLedger one milestone per Claude Code session, using plan mode first, tests with every change, and a "teach-back" at the end so you can defend every line in interviews.

**One-time setup**

1. Create the GitHub repository `trustledger` and clone it locally.
2. Export this document as Markdown and save it in the repo as `docs/SPEC.md`. Claude Code reads files from the repo, so the spec must live there.
3. Copy the CLAUDE.md content from the "CLAUDE.md for the repository" section into `CLAUDE.md` at the repo root. Claude Code loads it automatically in every session.
4. Create an empty `LEARNING.md` at the repo root for your own notes.

**Loop for every milestone (M0 to M16)**

1. Create a branch: `git checkout -b feature/m5-ledger`.
2. Start a fresh Claude Code session in the repo (or run `/clear`), so the context holds only this milestone.
3. Switch to **plan mode** (Shift+Tab), paste the milestone prompt from this guide, and read the plan. Push back on anything that is not in the spec.
4. Approve the plan and let Claude implement it, including tests.
5. Run `./mvnw verify` yourself and read the whole diff. Don't accept code you can't explain.
6. Run `/code-review` on the change and fix real findings.
7. Run the **teach-back prompt**: *"Explain what we built in this milestone as a senior fintech interviewer would ask about it. Then quiz me with 5 questions, one at a time, and correct my answers."*
8. Add 3-5 lines to `LEARNING.md`, commit using Conventional Commits (`feat(ledger): ...`), push, and open a PR to `main` so the history looks professional.

**Rules that protect your interview**

- **Write the core yourself.** For the ledger posting service (M5) and the transfer flow (M6), ask Claude to write the failing tests and the scaffolding, then write the core method yourself and ask Claude to review it. Interviewers dig deepest here.
- **No new libraries without a reason.** If Claude proposes a dependency that is not in the spec, ask why, and record the decision as an ADR in `docs/adr/`.
- **Never paste real secrets or personal data.** Use test keys (Razorpay test mode) and fake test users only. Keep secrets in `.env`, which is git-ignored.
- **Keep sessions small.** If a session gets long or confused, commit what works, `/clear`, and continue with a short summary.
- **Be honest about AI use.** If asked, say you used Claude Code as a pair programmer while you made the design decisions and reviewed every change. That is normal in 2026 and well received, as long as you can explain the code.

## 1. Prerequisites and tool setup

Install these before M0; the full local stack needs about 8 GB of free RAM, so 16 GB on your laptop is comfortable.

| Tool | Version | Why | Check |
| --- | --- | --- | --- |
| JDK (Eclipse Temurin) | 25 LTS (21 LTS also fine) | Language features, virtual threads | `java -version` |
| IntelliJ IDEA | Latest | IDE, HTTP client files, debugger | - |
| Docker Desktop (WSL 2 backend on Windows) | Latest | Postgres, Kafka, Redis, Keycloak, LocalStack, Grafana | `docker compose version` |
| Git + GitHub CLI | Latest | Version control, PRs | `git --version`, `gh --version` |
| Claude Code | Latest | Pair programmer | `claude --version` |
| Maven | Via the Maven wrapper (`./mvnw`) in the repo | Build | `./mvnw -v` |
| k6 or Gatling | Latest | Load tests (M13) | `k6 version` |
| AWS CLI v2 | Latest | Deployment (M14) | `aws --version` |

**Accounts to create yourself**

- **Razorpay** account in **test mode** only, for test API keys (M7). Never use live keys.
- **AWS** account. Before creating anything, set a monthly budget of USD 10 with an email alert in AWS Budgets.
- **GitHub** public repository named `trustledger`.
- Optional: an Anthropic API key for the Spring AI feature (M15).

**If your laptop has only 8 GB RAM:** run the observability containers only when you need them (they sit in a separate Docker Compose profile, set up in M0), and close the browser tabs you don't need while running tests.

## 2. Product requirements

TrustLedger is a prepaid digital wallet: customers complete KYC, add money through Razorpay, send money to each other, and every rupee is recorded in a double-entry ledger, checked by AML rules and written to a tamper-evident audit trail. Requirement IDs (FR-...) are referenced by the milestone prompts.

**Actors**

| Role | Can do |
| --- | --- |
| CUSTOMER | Register, give consent, complete KYC, add money, send money, view history and statements, request data export or erasure |
| COMPLIANCE\_OFFICER | Review KYC (maker or checker), approve held transfers, freeze wallets, work AML cases |
| ADMIN | Run reconciliation, view ledger account balances, manage configuration |
| AUDITOR | Read-only access to audit events and the chain verification result |
| SYSTEM | Scheduled jobs (reconciliation, pending top-up checks) and the AML service |

**Functional requirements**

*Identity and consent*

- FR-ID-1: Users register and log in through Keycloak (OIDC). The API never handles passwords.
- FR-ID-2: On a user's first authenticated request, create their `users` row from the JWT (just-in-time provisioning).
- FR-ID-3: Record consent for TERMS and PRIVACY (required) and MARKETING (optional) with policy version, time and IP. Users can withdraw MARKETING.
- FR-ID-4: Keycloak requires OTP (MFA) for COMPLIANCE\_OFFICER and ADMIN users.

*KYC*

- FR-KYC-1: Minimum KYC: name, date of birth, mobile number, PAN. Valid formats move the case to MIN\_KYC automatically (OTP check is simulated).
- FR-KYC-2: Full KYC: address plus document uploads (PDF/JPG/PNG, at most 5 MB each) stored in S3. The case moves to SUBMITTED.
- FR-KYC-3: Maker-checker: officer A verifies (SUBMITTED → VERIFIED), a different officer B approves (VERIFIED → APPROVED). Either can reject with a reason.
- FR-KYC-4: Approval publishes `KycLevelUpgraded` (MIN or FULL).

*Wallet*

- FR-W-1: A wallet is created automatically when a user reaches MIN\_KYC; its tier is upgraded to FULL on full-KYC approval.
- FR-W-2: Limits depend on tier (table below). Available balance = balance minus held amount.
- FR-W-3: Compliance officers can freeze and unfreeze a wallet with a reason. A frozen wallet can't send or receive.

*Ledger*

- FR-L-1: Every money movement is one journal entry with at least two lines, and total debits equal total credits.
- FR-L-2: Journal entries and lines are immutable; the database rejects UPDATE and DELETE.
- FR-L-3: Corrections are reversal entries that point to the entry they reverse.
- FR-L-4: A nightly reconciliation job checks each wallet's stored balance against its ledger-derived balance, and alerts on any mismatch.

*Payments*

- FR-P-1: P2P transfer to another wallet by recipient mobile number or wallet ID. An `Idempotency-Key` header is required.
- FR-P-2: Transfers check KYC tier, wallet status, available balance, daily and monthly limits, and the receiver's balance cap.
- FR-P-3: Transfers at or above the high-value threshold are HELD\_FOR\_REVIEW: the amount is held, and a compliance officer approves or rejects.
- FR-P-4: Add money: create a Razorpay order, the customer pays in test mode, the `payment.captured` webhook credits the wallet.
- FR-P-5: Webhooks are verified by HMAC signature and de-duplicated by event ID.
- FR-P-6: A scheduled job checks top-ups still PENDING after 15 minutes against the Razorpay API.

*AML (separate service)*

- FR-AML-1: Consume `TransferCompleted` and `TopupCaptured` events and evaluate rules R1-R4 (table below).
- FR-AML-2: Each rule hit creates an alert; alerts for one wallet group into an open case.
- FR-AML-3: A HIGH-severity alert publishes `AccountFreezeRequested`; the core service freezes the wallet.
- FR-AML-4: Case workflow: OPEN → INVESTIGATING → CLOSED\_FALSE\_POSITIVE or ESCALATED (STR). An escalated case can be exported as a CSV report.

*Audit, privacy, notifications, statements*

- FR-A-1: Every state-changing action writes an audit event (actor, role, action, entity, masked before/after, IP, correlation ID), hash-chained to the previous event.
- FR-A-2: Auditors can verify the hash chain through an API endpoint.
- FR-PR-1: Personal data (PAN, date of birth, address, mobile, email) is encrypted at rest and masked in responses (for example `XXXXX1234X`).
- FR-PR-2: Users can request a data export (JSON) and erasure. Erasure anonymises personal fields but keeps ledger, transfer and audit records for the retention period.
- FR-N-1: Email notifications for transfer sent/received, KYC decision and wallet frozen.
- FR-S-1: Transaction history with pagination, sorting and filters (date range, type). FR-S-2: Monthly statement as a PDF.

**Wallet limits (configurable in `application.yml`)**

| Rule | MIN\_KYC wallet | FULL\_KYC wallet | Source |
| --- | --- | --- | --- |
| Maximum balance | ₹10,000 | ₹2,00,000 | RBI PPI directions (verify current values) |
| Money added per month | ₹10,000 | ₹2,00,000 | RBI PPI directions (verify) |
| P2P transfers | Not allowed | Allowed | RBI: small PPIs can't transfer funds (verify) |
| Transfers per day | - | ₹1,00,000 | Own policy |
| High-value hold threshold | - | ₹50,000 or more | Own policy |

**AML rules (configurable)**

| Code | Rule | Severity |
| --- | --- | --- |
| R1 Velocity | More than 5 outgoing transfers within 10 minutes | MEDIUM |
| R2 Structuring | 3 or more transactions between ₹45,000 and ₹49,999 within 24 hours | HIGH |
| R3 Large amount | A single transaction of ₹1,00,000 or more | MEDIUM |
| R4 Fan-out | Transfers to 10 or more distinct recipients within 24 hours | HIGH |

**Non-functional requirements**

- Correctness: zero balance drift under concurrent load; a request retried with the same idempotency key never moves money twice.
- Performance target: transfer API p99 under 300 ms at 100 requests per second on your laptop. Measure it in M13 and report the real number.
- Security: OWASP API Top 10 addressed; deny by default; personal data never in logs.
- Retention: ledger, transfer and audit records kept at least 5 years (configurable).
- Quality: at least 80% line coverage on `domain` and `application` packages; all module boundary tests pass.

## 3. Architecture and repository structure

TrustLedger is a modular monolith (`core-app`, built with Spring Modulith) plus one separate microservice (`risk-aml-service`) connected through Kafka. Inside each module, code is layered: web → application → domain, with infrastructure adapters at the edge (hexagonal style).

&#91;embedded content: TrustLedger architecture · core-app, AML service, data stores\]

A transfer locks the wallets and posts to the ledger in one PostgreSQL transaction; events then leave through the outbox to Kafka, where the AML service can send back a freeze request.

**Repository layout**

```text
trustledger/
├── CLAUDE.md                     # instructions Claude Code loads every session
├── README.md  LEARNING.md
├── pom.xml                       # parent POM (packaging: pom)
├── docs/  SPEC.md  adr/  diagrams/  perf.md  security-checklist.md
├── event-contracts/              # Kafka event records shared by both apps (versioned: ...V1)
├── core-app/
│   └── src/main/java/com/trustledger/core/
│       ├── TrustLedgerApplication.java
│       ├── shared/        # Money, error codes, security helpers, crypto, web config (open module)
│       ├── identity/      # users, consents, JIT provisioning
│       ├── kyc/           # KYC cases, documents, maker-checker
│       ├── wallet/        # wallets, limits, freeze
│       ├── ledger/        # accounts, journal entries, reconciliation
│       ├── payments/      # transfers, top-ups, webhooks, idempotency
│       ├── audit/         # hash-chained audit log
│       ├── privacy/       # data export, erasure
│       ├── notification/  # email
│       └── reporting/     # history, statements
├── risk-aml-service/             # separate Spring Boot app, own schema
├── infra/  docker-compose.yml  keycloak/  grafana/  aws/
├── http/                         # IntelliJ .http request files for every endpoint
├── load-tests/
└── .github/workflows/ci.yml
```

**Inside one module (example: `wallet`)**

```text
wallet/
├── WalletApi.java            # public interface other modules may call
├── WalletFrozen.java         # public domain event (record)
├── WalletView.java           # public read model (record)
├── package-info.java         # @ApplicationModule(displayName = "Wallet")
├── web/                      # controllers, request/response records
├── application/              # services: use cases, @Transactional
├── domain/                   # entities, value objects, domain rules
└── infrastructure/           # JPA repositories, external clients
```

Spring Modulith treats a module's base package as its public API and its sub-packages as internal, so other modules can only see `WalletApi`, `WalletFrozen` and `WalletView`.

**Architecture rules (enforced by tests)**

1. A module talks to another only through that module's base-package API or by listening to its events. Never through its repositories or entities.
2. `ledger` depends on no other business module. Others call `LedgerApi.post(...)`.
3. Money movement is synchronous and atomic in one database transaction (payments → wallet + ledger). Everything else (notifications, audit, AML) reacts to events.
4. Controllers call application services only. JPA entities never leave their module; APIs return records.
5. No foreign keys across module schemas; modules reference each other by ID.
6. No remote calls (Razorpay, S3, Kafka) inside a database transaction that holds row locks.
7. `ApplicationModules.of(TrustLedgerApplication.class).verify()` and ArchUnit layer tests run in CI.

**Why this design (for interviews):** a modular monolith gives clear boundaries with one deployment and real ACID transactions for money. AML is a separate service because it scales and changes independently, and it shows event-driven integration. Any module can later be extracted into a service along the same boundary.

## 4. Domain model and database schema

One PostgreSQL database with one schema per module (`identity`, `kyc`, `wallet`, `ledger`, `payments`, `audit`, `privacy`, `notification`) plus a separate `aml` schema owned by the AML service. Money is stored as `bigint` paise, IDs are UUIDs, and timestamps are `timestamptz` in UTC.

**Core tables** (every table also has `created_at`; mutable tables have `updated_at` and `version` for optimistic locking)

| Table | Key columns | Constraints and notes |
| --- | --- | --- |
| `identity.users` | `id`, `keycloak_subject`, `email_enc`, `email_hash`, `phone_enc`, `phone_hash`, `full_name_enc`, `status` (ACTIVE, ERASED) | `keycloak_subject` unique; `phone_hash` unique (HMAC blind index for lookups on encrypted data) |
| `identity.consents` | `id`, `user_id`, `purpose` (TERMS, PRIVACY, MARKETING), `policy_version`, `granted`, `captured_at`, `ip` | Append-only history; the latest row per purpose wins |
| `kyc.kyc_cases` | `id`, `user_id`, `level` (MIN, FULL), `status` (SUBMITTED, VERIFIED, APPROVED, REJECTED), `pan_enc`, `dob_enc`, `address_enc`, `verified_by`, `approved_by`, `rejection_reason` | Check: `approved_by <> verified_by` (maker-checker in the database too) |
| `kyc.kyc_documents` | `id`, `case_id`, `doc_type`, `s3_key`, `content_type`, `size_bytes`, `sha256` | Size at most 5 MB; files live in S3, never in the database |
| `wallet.wallets` | `id`, `user_id`, `tier` (MIN, FULL), `status` (ACTIVE, FROZEN, CLOSED), `balance_minor`, `held_minor`, `currency`, `ledger_account_id`, `version` | `user_id` unique; checks `balance_minor >= 0`, `held_minor >= 0`, `held_minor <= balance_minor` |
| `wallet.wallet_freezes` | `id`, `wallet_id`, `source` (COMPLIANCE, AML), `reason`, `frozen_by`, `frozen_at`, `unfrozen_by`, `unfrozen_at` | Freeze history for audits |
| `ledger.ledger_accounts` | `id`, `code`, `type` (ASSET, LIABILITY, REVENUE), `normal_side` (DEBIT, CREDIT), `currency` | `code` unique, for example `WALLET:<walletId>`, `PLATFORM:GATEWAY_CLEARING` |
| `ledger.journal_entries` | `id`, `entry_type` (TOPUP, P2P\_TRANSFER, REVERSAL), `reference_type`, `reference_id`, `reverses_entry_id`, `posted_at` | Unique (`reference_type`, `reference_id`, `entry_type`) so the same business event can't be posted twice |
| `ledger.journal_lines` | `id`, `entry_id`, `account_id`, `direction` (DEBIT, CREDIT), `amount_minor`, `line_no` | Check `amount_minor > 0`; index on (`account_id`, `entry_id`) |
| `payments.transfers` | `id`, `sender_wallet_id`, `receiver_wallet_id`, `amount_minor`, `note`, `status` (INITIATED, HELD\_FOR\_REVIEW, COMPLETED, REJECTED, FAILED, REVERSED), `journal_entry_id`, `reviewed_by`, `version` | Index on (`sender_wallet_id`, `created_at`) for limit checks and history |
| `payments.topups` | `id`, `wallet_id`, `amount_minor`, `gateway_order_id`, `gateway_payment_id`, `status` (CREATED, PENDING, CAPTURED, FAILED, EXPIRED), `journal_entry_id`, `version` | `gateway_order_id` unique |
| `payments.webhook_events` | `id`, `gateway_event_id`, `event_type`, `payload` (jsonb), `signature_valid`, `processed_at` | `gateway_event_id` unique (de-duplication) |
| `payments.idempotency_keys` | `user_id`, `idem_key`, `request_hash`, `status` (IN\_PROGRESS, COMPLETED), `response_status`, `response_body` (jsonb), `expires_at` | Primary key (`user_id`, `idem_key`); kept 24 hours |
| `audit.audit_events` | `seq` (bigserial), `occurred_at`, `actor_id`, `actor_role`, `action`, `entity_type`, `entity_id`, `before`/`after` (jsonb, masked), `ip`, `correlation_id`, `prev_hash`, `hash` | Append-only; `hash` = SHA-256 of `prev_hash` + the event's canonical JSON |
| `privacy.data_requests` | `id`, `user_id`, `type` (EXPORT, ERASURE), `status`, `result_s3_key` | - |
| `event_publication` | Created by Spring Modulith | The outbox: events stored in the same transaction, then sent to Kafka |
| `aml.transaction_facts` | `event_id`, `wallet_id`, `counterparty_wallet_id`, `amount_minor`, `direction`, `occurred_at` | `event_id` unique; index on (`wallet_id`, `occurred_at`) for rule windows |
| `aml.alerts`, `aml.cases` | Alert: `rule_code`, `severity`, `details`, `case_id`. Case: `wallet_id`, `status`, `assigned_to`, `resolution`, `ai_summary` | One open case per wallet |
| `aml.processed_events` | `event_id` (primary key) | Idempotent consumer: skip events already processed |

**Database-level safety nets (Flyway migrations)**

- A trigger on `journal_entries`, `journal_lines` and `audit_events` that raises an error on UPDATE or DELETE.
- A deferred constraint trigger that, at commit, checks each journal entry's debits equal its credits.
- Check constraints on balances and amounts (listed above).

**Ledger posting rules**

| Business event | Debit | Credit |
| --- | --- | --- |
| Top-up captured (₹X) | `PLATFORM:GATEWAY_CLEARING` (asset) ₹X | `WALLET:<id>` (liability) ₹X |
| P2P transfer (₹X) | `WALLET:<sender>` ₹X | `WALLET:<receiver>` ₹X |
| Reversal of entry E | Each credit line of E | Each debit line of E |

A customer's wallet balance is money the platform owes the customer, so wallet accounts are liabilities with a credit normal side.

**Transfer flow (one database transaction)**

1. Idempotency check: look up (`user_id`, `Idempotency-Key`). COMPLETED → return the stored response. IN\_PROGRESS → 409. A different request body with the same key → 422. New → insert IN\_PROGRESS (the primary key blocks a racing duplicate).
2. Validate: the caller owns the sender wallet, the receiver exists and is not the sender, the amount is positive with at most 2 decimals.
3. Lock both wallets with `SELECT ... FOR UPDATE`, **always in ascending wallet-ID order** so two opposite transfers can't deadlock.
4. Check both wallets are ACTIVE, the sender's tier allows P2P, available balance, daily and monthly limits, and the receiver's balance cap.
5. If the amount is at or above the high-value threshold: add it to `held_minor`, set status HELD\_FOR\_REVIEW, and stop. The ledger posting happens on approval.
6. Otherwise: post the journal entry through `LedgerApi`, update both cached balances, set the transfer to COMPLETED.
7. Publish `TransferCompleted`. Spring Modulith writes it to `event_publication` in the same transaction.
8. Commit, then save the response against the idempotency key. After commit, the event goes to Kafka for AML and notifications.

## 5. API specification

All endpoints sit under `/api/v1` (path versioning configured with Spring Framework 7's API versioning support), need a Keycloak bearer token unless marked public, and return errors as RFC 9457 ProblemDetail.

**Core app (port 8080)**

| Method | Path | Role | Notes |
| --- | --- | --- | --- |
| GET | `/me` | Any | Profile; creates the user row on first call |
| GET, POST | `/me/consents` | CUSTOMER | Read or record consent |
| POST | `/kyc/min` | CUSTOMER | Minimum KYC |
| POST | `/kyc/full` | CUSTOMER | Full KYC details; returns case ID |
| POST | `/kyc/cases/{id}/documents` | CUSTOMER (owner) | Multipart upload |
| GET | `/kyc/me` | CUSTOMER | Own KYC status |
| GET | `/compliance/kyc-cases?status=` | COMPLIANCE\_OFFICER | Paginated queue |
| POST | `/compliance/kyc-cases/{id}/verify` | COMPLIANCE\_OFFICER | Maker step |
| POST | `/compliance/kyc-cases/{id}/approve` | COMPLIANCE\_OFFICER | Checker step; must differ from the maker |
| POST | `/compliance/kyc-cases/{id}/reject` | COMPLIANCE\_OFFICER | Reason required |
| GET | `/compliance/kyc-cases/{id}/documents/{docId}` | COMPLIANCE\_OFFICER | Short-lived S3 presigned URL; access is audited |
| GET | `/wallets/me` | CUSTOMER | Balance, held, available, tier, limits used |
| GET | `/wallets/me/transactions` | CUSTOMER | `from`, `to`, `type`, `page`, `size`, `sort` |
| GET | `/wallets/me/statements/{yyyy-MM}` | CUSTOMER | PDF |
| POST | `/compliance/wallets/{id}/freeze`, `/unfreeze` | COMPLIANCE\_OFFICER | Reason required |
| POST | `/transfers` | CUSTOMER | `Idempotency-Key` required |
| GET | `/transfers/{id}` | CUSTOMER (sender or receiver) | - |
| GET | `/compliance/transfers?status=HELD_FOR_REVIEW` | COMPLIANCE\_OFFICER | Review queue |
| POST | `/compliance/transfers/{id}/approve`, `/reject` | COMPLIANCE\_OFFICER | - |
| POST | `/topups` | CUSTOMER | `Idempotency-Key` required; returns Razorpay order ID and key ID |
| GET | `/topups/{id}` | CUSTOMER (owner) | - |
| POST | `/webhooks/razorpay` | Public | HMAC signature verified; no JWT |
| POST | `/admin/reconciliation/run` | ADMIN | Runs the job now |
| GET | `/admin/ledger/accounts/{code}/balance` | ADMIN, AUDITOR | Ledger-derived balance |
| GET | `/audit/events` | AUDITOR | Filters: entity, actor, date range |
| GET | `/audit/verify` | AUDITOR | Walks the hash chain and reports the first broken link |
| POST | `/me/data-export` | CUSTOMER | Async; result as presigned URL |
| POST | `/me/erasure-request` | CUSTOMER | Anonymises personal data, keeps financial records |
| GET | `/me/data-requests` | CUSTOMER | Status of export and erasure requests |

**AML service (port 8081)**

| Method | Path | Role | Notes |
| --- | --- | --- | --- |
| GET | `/aml/alerts` | COMPLIANCE\_OFFICER | Filters: rule, severity, wallet |
| GET | `/aml/cases`, `/aml/cases/{id}` | COMPLIANCE\_OFFICER | Case with its alerts and facts |
| POST | `/aml/cases/{id}/assign`, `/close`, `/escalate` | COMPLIANCE\_OFFICER | Workflow |
| GET | `/aml/cases/{id}/str-report` | COMPLIANCE\_OFFICER | CSV export |
| POST | `/aml/cases/{id}/ai-summary` | COMPLIANCE\_OFFICER | Optional (M15); a draft only |

**Headers**

- `Authorization: Bearer <JWT>` on every non-public endpoint.
- `Idempotency-Key: <UUID>` on `POST /transfers` and `POST /topups`.
- `X-Correlation-Id`: accepted if sent, generated if missing, always echoed back, and logged.

**Error format**

```json
{
  "type": "https://trustledger.dev/errors/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Available balance is lower than the transfer amount.",
  "instance": "/api/v1/transfers",
  "errorCode": "INSUFFICIENT_FUNDS",
  "correlationId": "7f9c..."
}
```

| errorCode | HTTP status |
| --- | --- |
| VALIDATION\_FAILED (with a `violations` list) | 400 |
| UNAUTHENTICATED / ACCESS\_DENIED / KYC\_REQUIRED | 401 / 403 / 403 |
| NOT\_FOUND | 404 |
| REQUEST\_IN\_PROGRESS / MAKER\_CHECKER\_VIOLATION / WALLET\_FROZEN | 409 |
| INSUFFICIENT\_FUNDS / LIMIT\_EXCEEDED / IDEMPOTENCY\_KEY\_REUSED | 422 |
| RATE\_LIMITED | 429 |
| GATEWAY\_UNAVAILABLE | 503 |

## 6. Cross-cutting conventions

These conventions apply to every milestone; they also go into CLAUDE.md so Claude Code follows them without being reminded.

| Topic | Convention |
| --- | --- |
| Money | Domain uses a `Money` record (`long amountMinor`, `Currency currency`). The database stores `bigint` paise. The API accepts amounts as decimal strings (`"1500.50"`); more than 2 decimals is rejected, never rounded. No `double` or `float` anywhere near money. |
| IDs | UUIDs (time-ordered UUIDv7 preferred) generated in the application. |
| Time | `Instant` in code, `timestamptz` in UTC in the database. Statements display Asia/Kolkata. Inject a `Clock` bean so tests can control time. |
| DTOs and mapping | Java records for requests, responses and events; MapStruct for mapping. Entities never leave their module. |
| Lombok | Only on JPA entities (`@Getter`, `@NoArgsConstructor(access = PROTECTED)`). No `@Data` or `@Setter` on entities; state changes go through domain methods such as `wallet.freeze(reason)`. |
| Validation | Bean Validation on request records, plus custom validators `@ValidPan`, `@ValidIndianMobile`, `@ValidAmount`. Domain objects check their own invariants too. |
| Errors | Domain exceptions extend `TrustLedgerException(ErrorCode)`. One `@RestControllerAdvice` maps them to ProblemDetail. Never return stack traces. |
| Transactions | `@Transactional` only on application services; `readOnly = true` for queries. No HTTP, S3 or Kafka calls while holding row locks. |
| Concurrency | Pessimistic row locks (in ascending ID order) for wallets during money movement; `@Version` optimistic locking everywhere else. |
| Security | Stateless resource server; deny by default. Keycloak `realm_access.roles` mapped to `ROLE_*` authorities. Ownership checks (`@PreAuthorize("@walletAccess.isOwner(#walletId)")`) stop BOLA. Actuator restricted to ADMIN, except health. Strict CORS allowlist. |
| Encryption | `EncryptedStringConverter` (AES-256-GCM, random 12-byte IV per value) on personal-data columns. Locally the key comes from an environment variable; on AWS from a KMS data key (envelope encryption). HMAC-SHA256 blind index for searchable fields (phone, email). |
| Logging | Spring Boot structured JSON logging (ECS format). MDC holds `correlationId` and `userId`. Never log personal data; a masking helper is the safety net. |
| Configuration | `@ConfigurationProperties` records with validation (`trustledger.limits.*`, `trustledger.aml.*`, `trustledger.razorpay.*`). Profiles: `local`, `test`, `aws`. Secrets only from environment variables or AWS Secrets Manager. |
| Database | Flyway migrations named `V{n}__{module}_{description}.sql`; `ddl-auto=validate`; `open-in-view=false`. |
| Events | In-process: records published with `ApplicationEventPublisher` and handled by `@ApplicationModuleListener`. To Kafka: `@Externalized("trustledger.payments.transfer-completed.v1::#{#this.walletId()}")`, so the wallet ID is the message key. |
| Kafka | Topic names `trustledger.<module>.<event>.v<n>`; JSON payloads; headers carry `eventId` and `correlationId`; consumers are idempotent and use a dead-letter topic after 3 retries. |
| Performance | Virtual threads on (`spring.threads.virtual.enabled=true`). |
| Git | Conventional Commits (`feat(payments): ...`), one branch and PR per milestone. |

## 7. CLAUDE.md for the repository

Save this as `CLAUDE.md` in the repo root in M0. Keep it short: Claude Code reads it at the start of every session, and the full detail lives in `docs/SPEC.md`.

```markdown
# TrustLedger

Regulated digital wallet (fintech portfolio project). Modular monolith `core-app`
(Spring Boot 4, Spring Modulith) + `risk-aml-service` (Spring Boot 4) over Kafka.
Full spec: docs/SPEC.md. Read the relevant section before every task.

## Commands
- Start infra: docker compose -f infra/docker-compose.yml up -d
- Start with observability: add --profile obs
- Build and test everything: ./mvnw verify
- Run core app: ./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local
- Run AML service: ./mvnw -pl risk-aml-service spring-boot:run -Dspring-boot.run.profiles=local

## Stack
Java 25, Spring Boot 4.x, Spring Security 7 (OAuth2 resource server, Keycloak),
Spring Data JPA + PostgreSQL + Flyway, Spring Modulith, Kafka, Redis, Resilience4j,
Bucket4j, MapStruct, Lombok (entities only), Testcontainers, JUnit, Mockito, AssertJ.

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
```

## 8. Milestones with Claude Code prompts

Build in this order: each milestone ends with green tests and a merged PR, so even if time runs out you always have a working, presentable project. Paste each prompt in plan mode in a fresh session.

### M0: Repository and local infrastructure (Week 1)

Goal: a multi-module Maven repo that builds in CI and a local stack that starts with one command.

- Parent POM with modules `event-contracts`, `core-app`, `risk-aml-service`; Maven wrapper; Java 25; latest Spring Boot 4.x from start.spring.io.
- `infra/docker-compose.yml`: PostgreSQL 17, Redis, Kafka (KRaft, single node), Keycloak (dev mode, realm import), Mailpit, LocalStack (S3, KMS). Observability containers under the `obs` profile.
- `.gitignore`, `.env.example`, README skeleton, `CLAUDE.md`, `docs/SPEC.md`, `docs/adr/0001-modular-monolith.md`.
- GitHub Actions workflow running `./mvnw verify`.
- `ModularityTests` in core-app.

Done when: `docker compose up -d` shows all containers healthy, both apps start with `/actuator/health` UP, and CI is green.

```text
Read CLAUDE.md and docs/SPEC.md sections "Architecture and repository structure" and "Prerequisites".
Milestone M0: set up the repository skeleton.
1. Parent POM (packaging pom) with modules event-contracts, core-app, risk-aml-service. Java 25,
   latest Spring Boot 4.x, Spring Modulith BOM, Testcontainers. Add the Maven wrapper.
2. core-app: web, validation, data-jpa, postgresql, flyway, security, oauth2-resource-server,
   actuator, modulith starter, kafka. risk-aml-service: web, data-jpa, postgresql, flyway,
   security, oauth2-resource-server, actuator, kafka.
3. infra/docker-compose.yml with healthchecks: postgres:17, redis, apache/kafka (KRaft),
   keycloak (start-dev, import infra/keycloak/realm-trustledger.json, leave the realm file empty
   for now), mailpit, localstack (s3, kms). Put prometheus/grafana in profile "obs" (we fill it in M12).
4. application.yml profiles local and test. Virtual threads on. open-in-view off.
5. ModularityTests using ApplicationModules.verify() and Documenter to write module diagrams.
6. .github/workflows/ci.yml running ./mvnw verify on push and PR (Docker is available on ubuntu runners).
7. .gitignore, .env.example, README skeleton.
Plan first and list every file you will create. Don't add business code yet.
```

### M1: Shared foundation (Week 1)

Goal: the building blocks every module uses.

- `Money` record with safe arithmetic, `ErrorCode` enum, `TrustLedgerException`, global ProblemDetail handler.
- Correlation-ID filter + MDC, structured JSON logging, `Clock` bean, springdoc OpenAPI, API versioning (`/api/v1`).
- `EncryptedStringConverter` (AES-256-GCM), `BlindIndex` (HMAC-SHA256), `Masking` helpers.
- Custom validators `@ValidPan`, `@ValidIndianMobile`, `@ValidAmount`.
- Flyway configured for multiple schemas.

Done when: unit tests cover Money (overflow, negative, currency mismatch, parsing "1500.50" and rejecting "1.005"), encryption round-trip and tamper detection, masking, validators, and a MockMvc test proves the error JSON shape.

```text
Read CLAUDE.md and docs/SPEC.md sections "Cross-cutting conventions" and "API specification" (headers and
error format). Milestone M1: build the shared module in core-app (com.trustledger.core.shared).
Create: Money record (long amountMinor, Currency) with add/subtract using Math.addExact/subtractExact and a
parse(String) that rejects more than 2 decimals; ErrorCode enum with the HTTP status mapping from the spec;
TrustLedgerException; GlobalExceptionHandler returning ProblemDetail with errorCode and correlationId;
CorrelationIdFilter (accept/generate/echo X-Correlation-Id, put into MDC); Clock bean; structured logging
config; springdoc; API versioning with path segment v1; EncryptedStringConverter (AES-256-GCM, random
12-byte IV, key from property trustledger.crypto.key); BlindIndex (HMAC-SHA256); Masking (PAN, phone, email);
validators @ValidPan, @ValidIndianMobile, @ValidAmount. Mark shared as an OPEN Modulith module.
Write thorough unit tests for each class, and one MockMvc test for the error format.
```

### M2: Identity, security and Keycloak (Week 1)

Goal: real OAuth2/OIDC security with roles, MFA for staff, rate limits and consent.

- Keycloak realm export: realm `trustledger`, roles CUSTOMER, COMPLIANCE\_OFFICER, ADMIN, AUDITOR; clients `trustledger-web` (public, PKCE) and `trustledger-dev` (direct access grants, **local testing only**); test users customer1, customer2, officer1, officer2, admin1, auditor1; OTP required for officers and admin.
- `SecurityFilterChain`: resource server with JWT, role mapping from `realm_access.roles`, method security, CORS, security headers.
- `identity` module: JIT user provisioning, `/me`, consents (FR-ID-1 to FR-ID-4).
- Bucket4j + Redis rate limiting: 20 requests/minute per user on money endpoints, 100 on others.
- `http/auth.http` file that gets a token and calls `/me`.

Done when: tests show 401 without a token, 403 with the wrong role, 200 with the right role; consent history is stored; the 21st transfer-endpoint request in a minute gets 429.

```text
Read CLAUDE.md and docs/SPEC.md sections "Product requirements" (actors, FR-ID-*) and "Cross-cutting
conventions" (security). Milestone M2: identity and security.
1. Write infra/keycloak/realm-trustledger.json as specified in the M2 deliverables of the spec (roles,
   two clients, six test users with local-only passwords, OTP required for COMPLIANCE_OFFICER and ADMIN).
2. SecurityConfig: stateless resource server, issuer from Keycloak, a JwtAuthenticationConverter mapping
   realm_access.roles to ROLE_*, deny by default, explicit matchers per path from the API table, method
   security on, CORS allowlist from config, security headers. /webhooks/razorpay is public.
3. identity module: users table (encrypted email/phone/name + blind indexes), consents table, Flyway
   migrations, JIT provisioning on first request, GET /me, GET/POST /me/consents.
4. Rate limiting with Bucket4j backed by Redis, limits from config, 429 with ProblemDetail RATE_LIMITED.
5. Tests: security matrix with spring-security-test jwt(), Testcontainers Postgres + Redis.
6. http/auth.http to fetch a token from Keycloak and call /me.
Also write docs/adr/0002-keycloak-instead-of-custom-auth.md.
```

### M3: KYC with maker-checker (Week 1)

Goal: KYC onboarding with document storage and the four-eyes approval rule.

- `kyc` module: minimum and full KYC (FR-KYC-1 to 4), encrypted personal fields, case state machine.
- Document upload to S3 (LocalStack locally): type checked by magic bytes, at most 5 MB, SHA-256 stored; officers view via presigned URLs valid 5 minutes.
- Maker-checker in the service **and** a database check constraint.
- Publishes `KycLevelUpgraded(userId, level)`.

Done when: tests cover each state transition, invalid PAN, oversize and wrong-type files, the same officer trying to verify and approve (409 MAKER\_CHECKER\_VIOLATION), and the event being published (Modulith `Scenario` test).

```text
Read CLAUDE.md and docs/SPEC.md (FR-KYC-*, kyc tables, KYC endpoints). Milestone M3: kyc module.
Implement the KYC case aggregate with explicit state transitions (invalid transitions throw), minimum KYC
auto-approval after format validation, full KYC with document upload to S3 through an S3 port with an
AWS SDK adapter (LocalStack in local/test), file checks (magic bytes, max 5 MB), SHA-256, presigned GET URLs
for officers. Maker-checker: verify by officer A, approve by different officer B, enforced in the service and
by a CHECK constraint. Publish KycLevelUpgraded as a public event record in the kyc base package.
Controllers per the API table with role rules and ownership checks. Tests: unit tests for the state
machine, integration tests with Testcontainers (Postgres + LocalStack), and a Spring Modulith Scenario test
that verifies KycLevelUpgraded is published on approval.
```

### M4: Wallet (Week 2)

Goal: wallets with KYC-tier limits and freezing.

- Wallet created by listening to `KycLevelUpgraded(MIN)`; tier upgraded on FULL (FR-W-1).
- Limits from `trustledger.limits.*` (FR-W-2), available = balance − held; freeze/unfreeze with history (FR-W-3), publishes `WalletFrozen`/`WalletUnfrozen`.
- `WalletApi` for the payments module: `lockForUpdate(List<UUID>)`, `debit`, `credit`, `hold`, `releaseHold`, `limitsFor`.

Done when: tests cover creation from the event, tier upgrade, limit calculation, freeze rules, and the API is the only way other modules touch wallets (Modulith verify).

```text
Read CLAUDE.md and docs/SPEC.md (FR-W-*, wallet tables, limits table). Milestone M4: wallet module.
Wallet aggregate with domain methods debit/credit/hold/releaseHold/freeze/unfreeze that enforce invariants
(non-negative, held <= balance, not frozen). Listen to KycLevelUpgraded with @ApplicationModuleListener to
create or upgrade wallets (idempotent: a second event changes nothing). LimitsProperties as a validated
@ConfigurationProperties record. WalletApi in the base package, including lockForUpdate(ids) that locks rows
with PESSIMISTIC_WRITE in ascending ID order. Freeze/unfreeze endpoints for COMPLIANCE_OFFICER, wallet
history table, WalletFrozen/WalletUnfrozen events. GET /wallets/me. Unit and integration tests.
The ledger account for each wallet is created in M5; leave a TODO port for it.
```

### M5: Double-entry ledger (Week 2)

Goal: the heart of the system. Write `LedgerPostingService.post()` yourself.

- Ledger accounts (one per wallet + platform accounts), journal entries and lines (FR-L-1 to 3).
- `LedgerApi.post(PostingCommand)` validates: at least 2 lines, debits = credits, same currency, accounts exist, positive amounts.
- Flyway triggers: no UPDATE/DELETE, deferred balanced-entry check.
- Reversal posting; nightly reconciliation job with ShedLock (FR-L-4), mismatch metric and log alert.

Done when: tests prove an unbalanced entry is rejected by the service **and** by the database, an UPDATE on `journal_lines` fails, a duplicate reference is rejected, and reconciliation detects a deliberately corrupted cached balance.

```text
Read CLAUDE.md and docs/SPEC.md (FR-L-*, ledger tables, posting rules). Milestone M5: ledger module.
I WILL WRITE LedgerPostingService.post() MYSELF. You: create the entities, repositories, Flyway migrations
(including the append-only triggers and the deferred constraint trigger that checks debits = credits per
entry), the LedgerApi interface and PostingCommand record, account creation for wallets (listen to
WalletCreated) and platform accounts (seeded by migration), reversal support, ledger-derived balance query,
and a ReconciliationJob (@Scheduled nightly + ShedLock) comparing wallet cached balances with ledger
balances. Then write FAILING tests for post(): balanced entry ok, unbalanced rejected, single line rejected,
currency mismatch rejected, zero/negative amount rejected, duplicate reference rejected, DB trigger blocks
UPDATE/DELETE. Stop after the tests and explain what post() must do, step by step. Don't implement it.
```

After writing `post()` yourself, ask: *"Review my LedgerPostingService.post() for correctness, concurrency and clarity. Don't rewrite it; list issues."*

### M6: P2P transfers, idempotency and concurrency (Week 2)

Goal: safe money movement. Write the core of `TransferService.transfer()` yourself.

- Transfer flow exactly as in "Transfer flow" (section 4): idempotency, ownership, ordered locking, limits, high-value hold, posting, event.
- `IdempotencyService` used by an aspect or filter on `@Idempotent` endpoints.
- Compliance approve/reject of HELD\_FOR\_REVIEW transfers (approver must be a compliance officer).
- `TransferCompleted` event.

Done when these tests pass:

1. Same `Idempotency-Key` sent twice → one transfer, same response; same key with a different body → 422.
2. 100 concurrent transfers from one wallet with balance for 50 → exactly 50 succeed, balance never negative, ledger reconciles.
3. A→B and B→A concurrently, 200 times → no deadlock, totals preserved.
4. Each rejection rule: frozen, MIN tier, insufficient funds, daily limit, receiver cap, self-transfer.
5. ₹50,000 transfer → HELD; approve → posted; reject → hold released.

```text
Read CLAUDE.md and docs/SPEC.md (FR-P-1..3, transfer tables, "Transfer flow", error codes).
Milestone M6: P2P transfers in the payments module.
Build: idempotency_keys table + IdempotencyService + @Idempotent annotation handled by an interceptor
(request hash = SHA-256 of the canonical body; IN_PROGRESS/COMPLETED; 24h expiry; cleanup job);
Transfer aggregate with state transitions; recipient lookup by mobile (blind index) or wallet ID;
limit checks using the transfers table; high-value hold flow and compliance approve/reject endpoints;
TransferCompleted event. I WILL WRITE THE BODY OF TransferService.transfer() MYSELF following the 8 steps
in the spec: create the class with the method signature, its collaborators, and the five test groups listed
in M6 "Done when" (use ExecutorService + CountDownLatch for the concurrency tests, Testcontainers Postgres).
Tests should fail until I implement the method. Explain why locking order prevents deadlocks.
```

### M7: Add money with Razorpay (test mode) (Week 3)

Goal: a real payment-gateway integration with webhooks, retries and reconciliation.

- `POST /topups` creates a Razorpay order through a `PaymentGateway` port (Spring `RestClient` adapter) wrapped in Resilience4j circuit breaker + timeout.
- A tiny `checkout.html` page (served locally) opens Razorpay Checkout in test mode.
- `POST /webhooks/razorpay`: verify the HMAC-SHA256 signature over the **raw body**, store the event, de-duplicate by event ID, process `payment.captured` / `payment.failed`.
- Top-up state machine; ledger posting on capture; limits checked (monthly load, balance cap); over-limit captured payments are refunded or parked in a suspense account (document your choice in an ADR).
- Scheduled check of PENDING top-ups older than 15 minutes (FR-P-6).

Done when: WireMock-based tests cover order creation, gateway timeout (503 GATEWAY\_UNAVAILABLE, circuit opens), invalid signature (rejected, stored as invalid), duplicate webhook (credited once), out-of-order events, and the reconciliation job. One manual end-to-end test payment in Razorpay test mode works.

```text
Read CLAUDE.md and docs/SPEC.md (FR-P-4..6, topups and webhook_events tables). Milestone M7: top-ups.
Implement the PaymentGateway port and a Razorpay adapter using RestClient (orders create, payment fetch)
with Resilience4j CircuitBreaker + TimeLimiter + Retry (retry only idempotent GETs). Keys from env
(RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET) - TEST MODE ONLY. Topup aggregate and state
machine. Webhook controller that reads the raw body, verifies X-Razorpay-Signature with HMAC-SHA256 in
constant time, stores the event, de-duplicates by event id, and processes it in its own transaction.
On capture: check limits, post the ledger entry (GATEWAY_CLEARING -> WALLET), publish TopupCaptured.
PendingTopupReconciler job. A static checkout.html for local manual testing. WireMock tests for all cases in
M7 "Done when". Write docs/adr/0006-over-limit-topups.md with the options and the choice.
```

### M8: Events, outbox and Kafka (Week 4)

Goal: reliable event delivery without dual writes.

- Spring Modulith JDBC event publication registry (the outbox) and Kafka externalization with `@Externalized`.
- `event-contracts` module: `TransferCompletedV1`, `TopupCapturedV1`, `WalletFrozenV1`, `AccountFreezeRequestedV1`.
- Incomplete publications re-sent on restart; completed ones cleaned up after 7 days.
- `notification` module: email via Mailpit for the FR-N-1 events.

Done when: an integration test (Testcontainers Kafka) shows a completed transfer produces exactly one Kafka message keyed by wallet ID with `eventId` and `correlationId` headers, and a test shows an event survives an application restart if Kafka was down.

```text
Read CLAUDE.md and docs/SPEC.md (Events and Kafka conventions, event_publication). Milestone M8.
Add spring-modulith-events-jdbc and spring-modulith-events-kafka. Create the event-contracts module with
versioned records. Map internal events to contract records and externalize them with @Externalized using
topic names from the conventions and the wallet ID as the key. Add eventId and correlationId headers.
Configure republishing of incomplete publications on startup and cleanup of completed ones.
Notification module: @ApplicationModuleListener handlers that send emails through Mailpit (SMTP), with
masked details. Tests: Testcontainers Kafka consumer asserting one message per transfer; restart scenario.
Write docs/adr/0005-outbox-with-spring-modulith.md explaining the dual-write problem.
```

### M9: Risk and AML service (Week 4)

Goal: a second, independent Spring Boot service that reacts to events.

- Own schema `aml`, own Flyway migrations, same Keycloak resource-server security.
- Idempotent Kafka consumers (`processed_events`), retries with backoff, dead-letter topic.
- Rules R1-R4 as separate `AmlRule` beans (Strategy pattern) with thresholds from config.
- Alerts → cases; HIGH severity publishes `AccountFreezeRequestedV1`; core-app listens and freezes the wallet (source AML).
- Case workflow endpoints and STR CSV export (FR-AML-1 to 4).

Done when: tests send crafted event sequences and assert each rule fires (and doesn't fire just below its threshold); a duplicate event is processed once; a poison message lands in the dead-letter topic; end-to-end, 3 transfers of ₹46,000 within 24 hours freeze the sender's wallet.

```text
Read CLAUDE.md and docs/SPEC.md (FR-AML-*, AML rules table, aml tables, AML endpoints). Milestone M9:
build risk-aml-service. Kafka consumers for TransferCompletedV1 and TopupCapturedV1 that record
transaction_facts idempotently (processed_events in the same transaction), DefaultErrorHandler with
exponential backoff (3 attempts) and a DeadLetterPublishingRecoverer. AmlRule interface + R1..R4
implementations using windowed SQL queries, thresholds in AmlProperties. RuleEngine creates alerts and groups
them into one open case per wallet; HIGH severity publishes AccountFreezeRequestedV1. In core-app, a listener
freezes the wallet with source AML. Case endpoints with COMPLIANCE_OFFICER role and STR CSV export.
Tests: per-rule boundary tests, duplicate event, poison message to DLT, and an end-to-end test with both apps'
containers if feasible (otherwise produce events directly). Write docs/adr/0007-separate-aml-service.md.
```

### M10: Audit trail and privacy (Week 5)

Goal: compliance features that very few portfolio projects have.

- `audit` module listens to all domain events plus an `@Audited` aspect on compliance and admin actions.
- Hash chain: each event stores `prev_hash` and `hash`; writes are serialised with a Postgres advisory lock so the chain stays linear.
- `/audit/verify` walks the chain; Hibernate Envers history on KYC cases and wallets.
- `privacy` module: data export (JSON to S3, presigned URL) and erasure (anonymise personal fields, keep financial records; block erasure while an AML case is open).

Done when: tests show every compliance action creates an audit event with masked data, editing a row directly in SQL makes `/audit/verify` report the exact broken sequence number, and erasure leaves the ledger intact while `users` personal fields are anonymised.

```text
Read CLAUDE.md and docs/SPEC.md (FR-A-*, FR-PR-*, audit and privacy tables). Milestone M10.
Audit: AuditEvent entity, AuditWriter that takes pg_advisory_xact_lock, reads the last hash, computes
SHA-256(prev_hash + canonical JSON) and inserts; listeners for all public domain events; an @Audited
annotation + aspect for compliance/admin endpoints capturing actor, role, IP, correlation ID and masked
before/after; GET /audit/events and GET /audit/verify for AUDITOR. Envers on kyc_cases and wallets.
Privacy: data export job writing JSON to S3 and returning a presigned URL; erasure that anonymises personal
fields (keeps ledger, transfers, audit) and is refused while an AML case is open (ask the AML service via a
simple REST call with a client-credentials token, or keep a local projection; explain the trade-off first).
Tests for all "Done when" items. Write docs/adr/0008-hash-chained-audit.md and 0009-field-encryption.md.
```

### M11: Statements and reporting (Week 5)

Goal: the read side, reusing your course's pagination and sorting skills.

- Transaction history with filters (date range, type), pagination and sorting using JPA Specifications; keyset pagination as an option for long histories.
- Monthly PDF statement (OpenPDF) with masked details and Asia/Kolkata times.
- Redis cache for rarely changing data (limits configuration, ledger account lookups). Never cache balances.

Done when: tests cover filters, sort, page boundaries, an empty month, and the PDF opening with correct totals.

```text
Read CLAUDE.md and docs/SPEC.md (FR-S-*, wallet endpoints). Milestone M11: reporting module.
Build a read model query for wallet transactions (transfers + top-ups) with JPA Specifications, filters
from/to/type, Pageable with a whitelist of sortable fields, and an optional keyset (cursor) variant.
Monthly statement PDF with OpenPDF: opening balance, entries, closing balance, masked counterparties,
IST times. @Cacheable with Redis for limits config and account lookups only, with TTLs. Tests for every
"Done when" item, including EXPLAIN ANALYZE notes for the history query in docs/perf.md.
```

### M12: Observability (Week 6)

Goal: dashboards and traces you can show in the demo video.

- `obs` profile: a single `grafana/otel-lgtm` container (Grafana, Prometheus-compatible metrics, Loki logs, Tempo traces) receiving OTLP from both apps.
- Custom metrics: `trustledger_transfers_total{status}`, transfer amount distribution, `trustledger_reconciliation_mismatches`, `trustledger_aml_alerts_total{rule}`, gateway circuit-breaker state.
- Traces that follow one transfer from the HTTP request through Kafka into the AML service.
- A provisioned Grafana dashboard JSON in `infra/grafana/`.

Done when: one transfer shows a single trace spanning both services, logs in Loki carry the trace ID, and the dashboard shows the custom metrics.

```text
Read CLAUDE.md and docs/SPEC.md (Observability in conventions). Milestone M12.
Add Spring Boot's OpenTelemetry/OTLP support to both apps, exporting traces, metrics and logs to a
grafana/otel-lgtm container in the compose "obs" profile. Make sure trace context propagates through Kafka
headers. Add the custom Micrometer metrics listed in M12. Secure actuator (health public, the rest ADMIN).
Provision a Grafana dashboard JSON: request rate, error rate, p95/p99 latency per endpoint, transfers by
status, AML alerts by rule, reconciliation mismatches. Document how to open it in README.
```

### M13: Performance and security hardening (Week 6)

Goal: real numbers for your resume and a written security review.

- k6 (or Gatling) scripts: transfers between 200 seeded wallets at increasing load; report throughput, p95, p99 and error rate in `docs/perf.md`.
- Compare virtual threads on vs off and Hikari pool sizes; add missing indexes found with `EXPLAIN ANALYZE`.
- `docs/security-checklist.md`: each OWASP API Top 10 (2023) item mapped to your control and the test that proves it.
- CI adds Trivy (image and dependency scan) and fails on critical vulnerabilities.

Done when: `docs/perf.md` has measured numbers (not estimates), the checklist covers all 10 items, and CI scans pass.

```text
Read CLAUDE.md and docs/SPEC.md (non-functional requirements). Milestone M13.
1. A seed script (Flyway repeatable migration in a "perf" profile, or a CommandLineRunner) creating 200
   FULL-tier wallets with balances. 2. k6 scripts that fetch tokens once and run transfers with unique
   idempotency keys at 10, 50, 100 req/s for 2 minutes each. 3. Help me run them, then write docs/perf.md
   with the REAL results I paste back, including a virtual threads on/off comparison. 4. Review queries with
   EXPLAIN ANALYZE and add indexes via Flyway. 5. docs/security-checklist.md mapping OWASP API Top 10 to
   controls and tests. 6. Add Trivy scanning to CI. Never invent numbers.
```

### M14: CI/CD and AWS deployment (Week 6)

Goal: a live demo on AWS, deployed by a pipeline, at minimum cost.

- Multi-stage Dockerfiles (non-root user, layered JAR) for both apps.
- GitHub Actions: test → scan → build images → push to Amazon ECR using GitHub OIDC (no long-lived AWS keys) → deploy.
- **Recommended low-cost setup:** one EC2 instance running the apps, Kafka, Keycloak and Redis with Docker Compose (`aws` profile), plus RDS PostgreSQL, S3 with SSE-KMS, a KMS key and Secrets Manager. ECS Fargate is a stretch goal.
- Caddy as reverse proxy for HTTPS if you have a domain.
- `infra/aws/README.md` with setup and **teardown** steps.

Done when: the pipeline deploys on merge to `main`, the demo works over the public URL, and you have recorded the demo video. Then stop or tear down resources to save money.

```text
Read CLAUDE.md and docs/SPEC.md ("Local infrastructure and AWS deployment"). Milestone M14.
Write multi-stage Dockerfiles (eclipse-temurin 25 JRE base, non-root user, layered jar extraction) for both
apps. A docker-compose.aws.yml for the EC2 host (apps, Kafka, Keycloak, Redis, Caddy) using RDS and S3/KMS via
an IAM instance role, secrets from AWS Secrets Manager. GitHub Actions deploy workflow: on main, build and push
to ECR with OIDC role assumption, then deploy to EC2 via SSM Run Command (no SSH keys in CI).
Write infra/aws/README.md with every console/CLI step for me to do myself, estimated monthly cost, a billing
alarm reminder and teardown commands. Don't create AWS resources yourself; I will run the commands.
```

### M15: AI-assisted AML case summary (optional, Week 7)

Goal: one modern, well-guarded AI feature.

- `POST /aml/cases/{id}/ai-summary` uses Spring AI with a Claude model (for example `claude-sonnet-5-5`) to draft a case narrative from alerts and transaction facts.
- Only masked or pseudonymised data is sent; transfer notes are passed as quoted data, never as instructions.
- Structured output into a record (`summary`, `riskFactors`, `suggestedNextSteps`), a timeout and a fallback message.
- Stored as a DRAFT; the officer must accept or edit it. The decision is always human, and the action is audited.

```text
Read CLAUDE.md and docs/SPEC.md (AML endpoints, M15). Milestone M15 in risk-aml-service.
Add Spring AI with the Anthropic integration (API key from env, model from config, default claude-sonnet-5-5).
Build CaseSummaryService: collect alerts and facts, pseudonymise (wallet IDs -> W1, W2; amounts kept; no
names, phones or PAN), put transfer notes inside a clearly delimited data block, and ask for structured output
into CaseSummaryDraft(summary, riskFactors, suggestedNextSteps). Timeout 20 s, fallback on failure.
Save as draft; accept/edit endpoint; audit both. Tests with a mocked ChatModel, including a prompt-injection
note ("ignore previous instructions...") that must not change the output schema.
```

### M16: Portfolio packaging (Week 7)

Goal: make the work easy for a recruiter to see in 2 minutes and for an interviewer to explore in 20.

- README, ADRs, diagrams, `http/` request collection, demo seed data, and a 5-minute demo video (see "README, ADRs, demo video and resume").

```text
Read the whole repo and docs/SPEC.md section "README, ADRs, demo video and resume". Milestone M16.
Rewrite README.md: one-paragraph pitch, architecture diagram (Mermaid), feature list mapped to compliance
rules, tech stack, "run locally in 3 commands", test and coverage summary, link to docs/perf.md and
docs/security-checklist.md, demo video link placeholder, and "design decisions" linking every ADR.
Check that every ADR referenced in the spec exists. Add a demo seed profile with customer1/customer2
balances. Then write a 5-minute demo script for me in docs/demo-script.md.
```

## 9. Testing strategy

The tests are part of the portfolio: a reviewer who opens the concurrency and idempotency tests immediately sees that you understand payments.

| Level | Tools | What it covers | Share |
| --- | --- | --- | --- |
| Unit | JUnit, AssertJ, Mockito | Money, domain aggregates and state machines, AML rules, validators, crypto | About 60% of tests |
| Slice | `@WebMvcTest`, `@DataJpaTest` + Testcontainers | Controllers (validation, security, error format), repositories and custom queries | About 20% |
| Integration | `@SpringBootTest` + Testcontainers (Postgres, Redis, Kafka, LocalStack), WireMock | Full flows: transfer, top-up + webhook, KYC, events to Kafka | About 15% |
| Architecture | Spring Modulith `verify()`, ArchUnit | Module boundaries, layer rules, no entities in controllers | Always on |
| Load | k6 or Gatling | Throughput and latency (M13) | Run manually |

**Must-pass tests before you call the project done**

1. Unbalanced journal entry rejected by the service and by the database.
2. UPDATE and DELETE on ledger and audit tables fail.
3. Duplicate `Idempotency-Key` returns the original response; same key with a different body returns 422.
4. 100 concurrent transfers never overdraw a wallet, and reconciliation reports zero mismatches.
5. Opposite-direction concurrent transfers never deadlock.
6. Every transfer rejection rule returns the right error code.
7. High-value transfer is held, then approved or rejected correctly.
8. Duplicate and invalid-signature webhooks are handled safely.
9. Gateway timeout opens the circuit breaker and returns 503.
10. KYC maker and checker must be different officers.
11. Security matrix: 401/403/200 for every endpoint group; user A can't read user B's wallet (BOLA).
12. Each AML rule fires at its threshold and not just below it; HIGH severity freezes the wallet.
13. A duplicate Kafka event is processed once; a poison message goes to the dead-letter topic.
14. Tampering with an audit row is detected by `/audit/verify`.
15. Erasure anonymises personal data and keeps financial records.
16. Module boundary tests pass.

Use one shared Testcontainers configuration (`@ServiceConnection` beans in a `TestcontainersConfiguration` class) so containers start once per test run and the build stays fast.

## 10. Local infrastructure and AWS deployment

Everything runs locally with Docker Compose; AWS is used only for the live demo, kept small and torn down after you record it.

**Local services (`infra/docker-compose.yml`)**

| Service | Image | Port | Used for |
| --- | --- | --- | --- |
| core-app | (built locally) | 8080 | Main API |
| risk-aml-service | (built locally) | 8081 | AML API and consumers |
| PostgreSQL | `postgres:17` | 5432 | All schemas (one database, `trustledger`) |
| Redis | `redis` | 6379 | Rate limits, idempotency fast path, cache |
| Kafka (KRaft) | `apache/kafka` | 9092 | Events |
| Keycloak | `quay.io/keycloak/keycloak` | 8180 | Identity provider |
| Mailpit | `axllent/mailpit` | 8025 (UI), 1025 (SMTP) | Email testing |
| LocalStack | `localstack/localstack` | 4566 | S3 and KMS locally |
| Grafana LGTM (profile `obs`) | `grafana/otel-lgtm` | 3000, 4317, 4318 | Dashboards, logs, traces |

Pin image versions in the compose file once things work, so the build stays reproducible.

**AWS (recommended low-cost setup for the demo)**

1. Region `ap-south-1` (Mumbai). Create the budget alarm first.
2. **RDS PostgreSQL** (smallest burstable class, single AZ, private, automated backups on).
3. **S3 bucket** for KYC documents and exports: Block Public Access on, default encryption SSE-KMS with your **KMS key**.
4. **Secrets Manager**: database password, Razorpay test keys, encryption key reference.
5. **ECR** repositories for both images.
6. **EC2** instance (4 GB RAM or more) with an IAM instance role allowing ECR pull, S3, KMS, Secrets Manager and SSM. It runs `docker-compose.aws.yml`: both apps, Kafka, Keycloak, Redis, Caddy.
7. **Security groups**: only 80/443 open to the internet; RDS reachable only from the EC2 security group. No SSH port: use SSM Session Manager.
8. **GitHub OIDC role** that lets the workflow push to ECR and send an SSM command. No AWS access keys in GitHub.

**Cost control**

- Run the AWS stack only for the 1-2 weeks around your demo and interviews; stop the EC2 and RDS instances when you aren't using them.
- Managed Kafka (Amazon MSK) and multi-AZ setups cost far more than a portfolio needs; mention them in interviews as the production choice instead.
- New AWS accounts usually come with free-tier credits; check what your account has before you start.
- `infra/aws/README.md` must include teardown commands, and you should run them after the job search.

## 11. README, ADRs, demo video and resume

Recruiters spend about 2 minutes on a repo, so the README and a short video must show the depth before anyone reads code.

**README structure**

1. One-paragraph pitch: "A regulated digital wallet with a double-entry ledger, KYC, AML monitoring and a tamper-evident audit trail."
2. Demo video link and the live URL (while it is up).
3. Architecture diagram (Mermaid) and module list.
4. Compliance features mapped to the rules they implement (KYC tiers, PPI limits, AML, maker-checker, DPDP, PCI scope).
5. Engineering highlights: idempotency, ordered locking, outbox, idempotent consumers, hash-chained audit, field encryption.
6. Tech stack.
7. Run locally in 3 commands.
8. Tests: what is covered, with the must-pass list and coverage numbers.
9. Performance results (link to `docs/perf.md`) and security checklist (link).
10. Design decisions: links to every ADR.

**ADRs to write** (`docs/adr/`, one page each: context, options, decision, consequences)

| # | Decision |
| --- | --- |
| 0001 | Modular monolith with Spring Modulith instead of microservices |
| 0002 | Keycloak (OIDC) instead of custom authentication |
| 0003 | Money as `long` paise, never floating point |
| 0004 | Pessimistic locking in ID order for wallet balances |
| 0005 | Transactional outbox with the Spring Modulith event registry |
| 0006 | Handling top-ups that would exceed wallet limits |
| 0007 | AML as a separate service over Kafka |
| 0008 | Hash-chained, append-only audit log |
| 0009 | Field-level encryption with blind indexes |

**5-minute demo video script**

1. (30 s) The problem and the architecture diagram.
2. (60 s) customer1 completes KYC; officer1 verifies, officer2 approves; the wallet appears.
3. (60 s) Add ₹5,000 through Razorpay test checkout; the webhook credits the wallet; show the journal entry.
4. (45 s) Send money with the same idempotency key twice: one transfer. Run the concurrency test: balance stays correct.
5. (45 s) Three ₹46,000 transfers trigger R2; the AML service freezes the wallet; the officer sees the case.
6. (30 s) Grafana: one trace across both services; `/audit/verify` passes, then fails after a manual SQL edit.
7. (30 s) Tests, CI pipeline and the load-test numbers.

**Resume bullets** (replace the brackets with your measured numbers)

- Built TrustLedger, a regulated digital-wallet backend (Java 25, Spring Boot 4, Spring Security/Keycloak, PostgreSQL, Kafka) with a double-entry ledger, KYC tiers, maker-checker approvals and AML monitoring.
- Prevented double spending with ordered pessimistic locking and idempotency keys; verified zero balance drift across \[N\] concurrent transfers with Testcontainers.
- Designed a modular monolith plus an event-driven AML microservice using a transactional outbox (Spring Modulith) and idempotent Kafka consumers with dead-letter handling.
- Sustained \[X\] transfers/second at p99 \[Y\] ms in k6 load tests; added OpenTelemetry tracing and Grafana dashboards across both services.
- Implemented a SHA-256 hash-chained audit trail, AES-GCM field encryption with KMS, and DPDP-style data export and erasure.

**LinkedIn:** post the demo video with a short write-up of one hard problem (for example "how I stopped double spending"), and pin the repo on your GitHub profile.

## 12. Seven-week schedule and definition of done

The core money path (M0-M7) must be done by 29 November, so you can show a working project in interviews from December even if later milestones slip.

| Week | Dates | Milestones | Shippable result |
| --- | --- | --- | --- |
| 1 | 9-15 Nov | M0, M1, M2, M3 | Secure API with Keycloak and KYC |
| 2 | 16-22 Nov | M4, M5, M6 | Wallets, ledger, safe P2P transfers |
| 3 | 23-29 Nov | M7 + catch-up | Add money via Razorpay test mode. **Core project ready for interviews** |
| 4 | 30 Nov-6 Dec | M8, M9 | Event-driven AML service |
| 5 | 7-13 Dec | M10, M11 | Audit trail, privacy, statements |
| 6 | 14-20 Dec | M12, M13, M14 | Observability, real performance numbers, live on AWS |
| 7 | 21-27 Dec | M15 (optional), M16 | Packaged portfolio and demo video |

**If you fall behind, cut in this order:** M15 (AI) → the AWS part of M14 (keep CI and record the demo video locally) → PDF statements in M11 → Envers in M10. Never cut tests on the money path, M8 or M9: those are what make the project stand out.

**Definition of done (whole project)**

- [ ] `./mvnw verify` passes locally and in CI, including all 16 must-pass tests
- [ ] `docker compose up -d` plus two run commands start everything on a clean machine
- [ ] Every endpoint in the API tables is implemented, secured and in the `http/` collection
- [ ] Swagger UI documents both services
- [ ] Grafana shows a cross-service trace and the custom metrics
- [ ] `docs/perf.md` has real measured numbers
- [ ] `docs/security-checklist.md` covers all OWASP API Top 10 items
- [ ] All 9 ADRs written
- [ ] README complete with diagram and demo video link
- [ ] No secrets in Git history (check with `git log -p | grep -i secret` and a secret scanner)
- [ ] You can explain every module, and you wrote the ledger posting and transfer core yourself
- [ ] `LEARNING.md` complete, used as your revision sheet
