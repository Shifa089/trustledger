# Changes

## DAY9: Milestone M6 part 1: idempotency keys, Transfer aggregate, recipient lookup, limit checks

**Goal:** the building blocks of the P2P transfer flow (SPEC §4 "Transfer flow", FR-P-1 to FR-P-3). `TransferService.transfer()` itself, `POST /transfers`, the compliance review endpoints and `TransferCompleted` come in part 2. The parts:
- **Migrations** `V10` (`payments.idempotency_keys`) and `V11` (`payments.transfers`) with the §4 indexes and check constraints.
- **Idempotency**: `IdempotencyService`, `@Idempotent`, a body-caching filter and a `HandlerInterceptor`, plus an hourly ShedLock **cleanup job**.
- **`Transfer` aggregate**: `INITIATED -> COMPLETED | HELD_FOR_REVIEW | FAILED`, `HELD_FOR_REVIEW -> COMPLETED | REJECTED`.
- **`RecipientResolver`** (mobile through `IdentityApi`, or wallet ID) and **`LimitChecker`** (daily and monthly sums with the injected `Clock`, days and months in Asia/Kolkata).

Branch: `feature/m6-payments-part1` (created from `master`), merged into `master`.

Decisions approved in the plan (the recommended options):
1. **Monthly limit = own policy.** The §2 table has no monthly *transfer* limit, only "money added per month" (top-ups, M7). An optional `trustledger.limits.full.monthly-transfer` (default `"500000.00"`) is added. When set, it must be at least `daily-transfer`, and it isn't allowed on a tier without P2P. SPEC §2 has a new row for it.
2. **Only 2xx responses are stored against a key.** Any other outcome (4xx business refusal, 5xx) deletes the IN_PROGRESS row. The business transaction rolled back, so no money moved, and a retry with the same key may run again (for example after a top-up fixes `INSUFFICIENT_FUNDS`).

---

### 1. How to verify Day 9

Run these from `dev/`. Start **Docker Desktop** first. Every cell holds exactly **one** command; paste them one at a time.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=TransferTest,RequestHasherTest,IdempotencyServiceTest,RecipientResolverTest,LimitCheckerTest,PaymentsPropertiesTest,LimitsPropertiesTest"` | The unit tests: state machine, canonical hash, claim decisions, recipient rules, IST day/month windows. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=PaymentsMigrationIT,IdempotencyServiceIT,IdempotencyInterceptorIT,RecipientResolverIT,TransferRepositoryIT"` | The new ITs against real PostgreSQL, including **20 concurrent claims → exactly 1 winner** and the HTTP-level replay/422/409 tests. |
| 4 | `./mvnw -pl core-app test "-Dtest=ModularityTests"` | payments → identity and wallet only through `IdentityApi` / `WalletApi`. |
| 5a | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak localstack` | Starts the local infrastructure. |
| 5b | `./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"` (in a **second** terminal) | Flyway applies **V10** and **V11**. Wait for `Started TrustLedgerApplication`. |
| 6 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "\d payments.idempotency_keys"` | The table, its primary key `(user_id, idem_key)`, the checks and `ix_idempotency_keys_expires_at`. |
| 7 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "\d payments.transfers"` | The table, its checks and `ix_transfers_sender_created`. |

There is no new `.http` file: `POST /transfers` is still the 501 stub. The interceptor is tested through a test-only endpoint (see Tests).

**Results:** `./mvnw clean verify` is **BUILD SUCCESS**: 721 unit tests and 424 integration tests green. Details in section 5.

---

### 2. What changed and why

#### Idempotency (FR-P-1, "Transfer flow" steps 1 and 8)

| Step | Where | What it does | Why |
|---|---|---|---|
| Cache the bodies | `web/IdempotencyBodyFilter` + `CachedBodyRequest` | Only for requests **with** an `Idempotency-Key` header. Reads the request body up front (at most `max-body-size` + 1 bytes) and wraps the response in a `ContentCachingResponseWrapper`. | Spring's `ContentCachingRequestWrapper` only caches what the controller has *already* read, which is too late to hash before the handler runs. A body over the limit isn't truncated: the rest is streamed, so other endpoints are unaffected. |
| Validate the key | `web/IdempotencyInterceptor.preHandle` | Only for `@Idempotent` handler methods. A missing or blank key, or one that isn't a UUID → 400 `VALIDATION_FAILED`. A body over 16 KB → 400. | SPEC §5: `Idempotency-Key: <UUID>`. A key is lower-cased, so upper and lower case are the same key. |
| Hash | `application/RequestHasher` | SHA-256 (hex) of the **canonical** JSON: parsed, keys sorted at every depth, no whitespace, decimals keep their scale. Invalid JSON → 400. | `{"a":1,"b":2}` and `{ "b": 2, "a": 1 }` are the same request; any changed value is a different one. |
| Claim | `IdempotencyService.claim` → `JdbcIdempotencyKeyRepository.claim` | `INSERT ... ON CONFLICT (user_id, idem_key) DO UPDATE ... WHERE expires_at <= now`, in its **own** transaction (`REQUIRES_NEW`). 1 row = claimed; 0 rows = read the existing row: different hash → 422 `IDEMPOTENCY_KEY_REUSED`, IN_PROGRESS → 409 `REQUEST_IN_PROGRESS`, COMPLETED → replay. | The **primary key** decides races (the user's requirement). The claim commits before the business work starts, so a racing duplicate sees it, and it survives that work's rollback. An expired key (24 h) is taken over in the same statement. |
| Replay | `preHandle` | Writes the stored status and JSON body, adds `Idempotent-Replayed: true`, and skips the controller. | The client gets the same answer; the money moves once. |
| Record the outcome | `afterCompletion` | 2xx → `complete` (stores status and body as `jsonb`); anything else → `release` (deletes the row). If storing fails, the key is left IN_PROGRESS and an ERROR is logged. | Decision 2. Left IN_PROGRESS is the safe side: a stuck key gives 409 until it expires, but never runs twice. |
| Clean up | `application/IdempotencyCleanupJob` | Hourly (`cleanup-cron`), ShedLock lock `payments-idempotency-cleanup`, deletes expired keys in batches of 1000, each batch its own transaction, `FOR UPDATE SKIP LOCKED`. | Same pattern as `ReconciliationJob` (ADR 0013). Correctness doesn't depend on it, since an expired key can be claimed anyway; it only keeps the table small. |

Keys are **per user** (PK `(user_id, idem_key)`): two customers can't collide or read each other's stored responses.

#### Payments module (new)

| File | Change | Why |
|---|---|---|
| `domain/Transfer` | The aggregate. `initiate` checks a positive INR amount, sender ≠ receiver, note ≤ 140 characters (blank → none). `complete(entry)`, `holdForReview`, `fail`, `approve(reviewer, entry)`, `reject(reviewer)`. Anything else → 409 `INVALID_STATE_TRANSITION`. UUIDv7 ID, `@Version`, Lombok `@Getter` only. | CLAUDE.md: state changes via domain methods, no setters. |
| `domain/TransferStatus`, `TransferRepository`, `infrastructure/JpaTransferRepository` | Port plus Spring Data. `sumOutgoingSince(sender, from, statuses)` is one JPQL `sum` on the `(sender_wallet_id, created_at)` index. | Limit checks. |
| `domain/IdempotencyKeyRepository`, `IdempotencyKeyRecord`, `IdempotencyStatus`, `infrastructure/JdbcIdempotencyKeyRepository` | Plain SQL through `JdbcClient` (joins the Spring transaction). | JPA can't express `INSERT ... ON CONFLICT`. |
| `application/RecipientResolver`, `TransferRecipient` | Exactly one of mobile or wallet ID (otherwise 400). Mobile → `IdentityApi.findByPhone` (blind index) → active user → `WalletApi.findByUser`. Wallet ID → new `WalletApi.findById`. Unknown number, erased user, no wallet and unknown wallet all give the **same** 404 "Recipient not found." Own wallet → 400. `TransferRecipient.toString()` masks the mobile. | Same 404 for every case: the endpoint can't be used to find out which mobile numbers are registered. Wallet status is checked later, under the lock. |
| `application/LimitChecker` | `checkSender`: tier without P2P → 403 `KYC_REQUIRED`; day and month sums since midnight / the 1st in `limit-zone` (Asia/Kolkata) from the injected `Clock`; COMPLETED and HELD_FOR_REVIEW count; `sum + amount > limit` → 422 `LIMIT_EXCEEDED` (landing exactly on the limit is allowed). `checkReceiverCap`: receiver balance + amount ≤ its tier's max balance. Messages never reveal the used amount or the receiver's balance. | FR-P-2. Held transfers count, or a customer could queue holds beyond the limit. **Must run under the sender's row lock** (documented): every transfer from that wallet takes that lock first, so the sum can't change between check and commit. |
| `application/PaymentsProperties`, `PaymentsConfig` | `trustledger.payments.limit-zone`, `idempotency.{ttl (1h-7d), max-body-size (1KB-1MB), cleanup-cron, cleanup-batch-size (1-10,000), lock-at-most-for}`. Startup fails on invalid values. | CLAUDE.md: config via validated `@ConfigurationProperties` records. |
| `web/Idempotent`, `IdempotencyInterceptor`, `IdempotencyBodyFilter`, `CachedBodyRequest`, `PaymentsWebConfig` | See the idempotency table. `PaymentsWebConfig` is a plain `@Configuration` that **declares** the `WebMvcConfigurer` as a bean. | `@WebMvcTest` slices scan `WebMvcConfigurer` classes. A config class that *was* one broke `GlobalExceptionHandlerTest` and `SwaggerPublicLocalTest`, because their slices had no `IdempotencyService`. Same pattern as `IdentityWebConfig`. |
| `package-info.java` | Module description. | |

#### Wallet module

| File | Change | Why |
|---|---|---|
| `WalletLimits` | New component `monthlyTransfer` (nullable; never set without P2P). | Decision 1. |
| `application/LimitsProperties.TierLimits` | New `monthlyTransfer` with validation; the old 4-argument constructor stays for tiers without it (`@ConstructorBinding` on the canonical one). | Existing tests and config keep working. |
| `WalletApi.findById` / `WalletApiImpl` | Read-only lookup by wallet ID, not locked. | `RecipientResolver` by wallet ID. |
| `application/MyWalletView.Limits` | Adds `monthlyTransfer` to `GET /wallets/me`. | The customer can see the limit that applies to them. |

#### Shared, database and configuration

| File | Change | Why |
|---|---|---|
| `V10__payments_create_idempotency_keys.sql` | PK `(user_id, idem_key)` (`uuid`); checks: status, hash `^[0-9a-f]{64}$`, response present exactly when COMPLETED and 2xx, `expires_at > created_at`; index on `expires_at`. | The database is the last line of defence. See "Fixes" for why the response check spells out `IS NOT NULL`. |
| `V11__payments_create_transfers.sql` | Columns per §4 plus `currency`, `reviewed_at`, `created_at`, `updated_at`. Checks: amount > 0, not self, INR, status list, `journal_entry_id` iff COMPLETED/REVERSED (and unique), `reviewed_by`/`reviewed_at` together, REJECTED needs a reviewer. Index `(sender_wallet_id, created_at)`. No FKs across schemas. | SPEC §4. |
| `shared/web/FilterOrder` | `IDEMPOTENCY_BODY = SECURITY + 30` (after JIT provisioning). | One place for filter order. |
| `application.yml` | `limits.full.monthly-transfer: "500000.00"`; `payments.limit-zone`, `payments.idempotency.*`. | |
| `docs/SPEC.md` §2, §4 | Monthly transfer row (own policy); extra `transfers` columns and checks; cleanup index and "only 2xx stored" on `idempotency_keys`. | Keep the spec the source of truth. |
| `ApiMockMvc` (test) | Adds `IdempotencyBodyFilter` to the MockMvc chain. | ITs use the production filter order. |

#### Tests

| File | Status | What it proves |
|---|---|---|
| `TransferTest` (33) | green | Every allowed step and **every forbidden step (parameterised, 20 cases) → `INVALID_STATE_TRANSITION`** with the status unchanged; amount, currency, self, note rules; null arguments are bugs. |
| `RequestHasherTest` (7) | green | Key order and whitespace don't matter, nested objects sorted, any value change matters, array order matters, `1.50` ≠ `1.5`, invalid JSON → 400, empty body = SHA-256 of "". |
| `IdempotencyServiceTest` (8) | green | 24 h expiry from the Clock; replay; 409; 422 whatever the state; a key released in between is claimed on retry; gives up with 409 after 3 attempts; only 2xx stored. |
| `RecipientResolverTest` (11) | green | Mobile, wallet ID, unknown/erased/no-wallet → identical 404, own wallet → 400, exactly one of the two, `toString` hides the number. |
| `LimitCheckerTest` (15) | green | **IST boundaries: 18:29:59Z is still today, 18:30:00Z is tomorrow; 30 Nov 18:30Z is already December.** Exactly at the limit OK, 1 paisa over refused; monthly limit; no monthly query without a monthly limit; MIN → `KYC_REQUIRED` with no query; receiver cap; messages don't leak amounts. |
| `PaymentsPropertiesTest` (7), `LimitsPropertiesTest` (+7) | green | Validation and binding of the new properties. |
| `PaymentsMigrationIT` (7) | green | Every check constraint by name, both primary/unique keys, both indexes. |
| `IdempotencyServiceIT` (11) | green | Against the real table: 24 h TTL, replay, 409/422, release, a COMPLETED key is never released, expired key taken over (response cleared), per-user keys, **20 concurrent claims → exactly 1 `Claimed` and 19 `REQUEST_IN_PROGRESS`**, cleanup deletes only expired rows, skips while the lock is held. |
| `IdempotencyInterceptorIT` (14) + test-only `IdempotencyProbeController` | green | **M6 "Done when" 1 at the HTTP level: same key twice → handler runs once, same status and body, `Idempotent-Replayed: true`; same key with another body → 422.** Missing/invalid key → 400 without running the handler; invalid JSON and oversized body → 400 and no row; reformatted body and upper-case key = same request; per-user keys; 4xx and 5xx release the key; **a duplicate while the first is still in the handler → 409**; **10 concurrent duplicates → handler runs once**; other endpoints ignore the header. |
| `TransferRepositoryIT` (5) | green | Round trip of every field, UUIDv7 IDs, a stale copy loses on `@Version`, `sumOutgoingSince` filters by sender, window (inclusive) and status. |
| `RecipientResolverIT` (5) | green | Real blind-index lookup (`9876543210` and `+91 9876543210`), wallet ID, unknown/malformed → 404, user without wallet → 404, own mobile/wallet → 400. |

**Fixes found while testing:**
1. **`@WebMvcTest` slices failed to start.** The first version of `PaymentsWebConfig` implemented `WebMvcConfigurer`. Slices include such classes, so the slices of other modules needed `IdempotencyService`. Fixed by declaring the configurer as a bean from a plain `@Configuration`.
2. **A real hole in `ck_idempotency_keys_response`.** I rewrote the check per status, so that an unknown status fails only the status check. In that rewrite, `status <> 'COMPLETED' OR response_status BETWEEN 200 AND 299` let a COMPLETED row with a `NULL` status through: `NULL BETWEEN ...` is NULL, and a CHECK that evaluates to NULL **passes**. `PaymentsMigrationIT` caught it, and the check now says `IS NOT NULL AND ...`.
3. Two test-side corrections: a migration test row that broke two checks at once, and an assertion that wrongly required at least one 409 among 10 concurrent duplicates. Fast handlers can serialise them all into replays; the real guarantee is "handler ran once".

---

### 3. Machine setup done on Day 9 (not in Git)

- **Branch** `feature/m6-payments-part1` created from `master`, committed and merged back into `master` (`--no-ff`). Not pushed.
- **No new dependencies**, no `.env` changes, no new Docker images. Docker Desktop must be running for the ITs.

### 4. Deferred (M6 part 2)

- **`TransferService.transfer()`**: you write the body, following the 8 steps. Part 2 adds the method signature, collaborators and the five failing "Done when" test groups (100 concurrent transfers with balance for 50, A↔B 200 times, each rejection rule, ₹50,000 hold → approve/reject).
- **`POST /transfers`** (replacing the 501 stub, `@Idempotent`), **`GET /transfers/{id}`** (sender or receiver), **`GET /compliance/transfers`**, **approve/reject**, and the **`TransferCompleted`** event.
- **The crash window**: the business commit succeeds, then the process dies before `complete()`. The key stays IN_PROGRESS (409) until it expires; the money moved once. Option for part 2: store the transfer ID with the key in the same transaction, so a retry can rebuild the response.
- **FAILED transfers**: a refusal inside the business transaction rolls back, so nothing is persisted as FAILED. Part 2 decides whether to record refused attempts in a separate transaction (useful for AML), or to keep FAILED for later failures only.
- **Ledger-consistent test funding**: `WalletFixtures.fund` credits without a ledger posting. The "ledger reconciles" check in part 2 needs fixtures that post a TOPUP entry too (as `ReconciliationIT.consistentWallet` does).

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS**.

- **Unit tests:** core-app **721/721 green**, up from 633 (+88). `ModularityTests` is green: payments uses only `IdentityApi`, `UserView`, `WalletApi`, `WalletView`, `WalletLimits` and the shared kernel.
- **Integration tests:** core-app **424/424 green**, up from 382 (+42).
- **AML service:** the IT passed (1/1).
- **JaCoCo (core-app):** 96.6% of instructions (417 of 12,179 missed) and 88.6% of branches (102 of 892 missed). The new `payments` packages: 93.6% instructions, 84.9% branches.

---

### 6. What to learn from Day 9

1. **Idempotency is a claim, not a lookup.** "Look up the key, and if it's absent run the request" has the same race as Day 8's duplicate check: two retries both see nothing. The fix is the same too: let a unique key decide. `INSERT ... ON CONFLICT` turns "check, then insert" into one atomic step whose answer (1 row or 0) *is* the decision.
2. **The claim must commit before the work starts, and outlive it.** If the IN_PROGRESS row lived inside the transfer's transaction, a duplicate would block on the uncommitted row instead of getting a quick 409, and a rollback would erase the evidence. `REQUIRES_NEW` gives the claim its own short transaction. That is also why `@Transactional` sits on the service, never on the interceptor.
3. **Only cache answers that can't change.** A stored 201 must be replayed: the money moved. A 422 `INSUFFICIENT_FUNDS` reflects a moment in time and moved nothing, so forgetting the key lets an honest retry succeed. Stripe stores both; we chose the version that is easier to reason about for a wallet.
4. **"Same request" needs a definition.** Hashing raw bytes would call a reformatted retry a *different* request (422) on clients that re-serialise JSON. Canonical JSON (sorted keys, no whitespace) defines sameness by meaning, not formatting, while still catching any changed amount or recipient.
5. **A day is a time zone decision.** `LocalDate.now()` on a UTC server says a transfer at 01:00 IST belongs to *yesterday*. Limits use `clock.instant().atZone(Asia/Kolkata)`, and the tests pin the edges (18:29:59Z vs 18:30:00Z). Only an injected `Clock` makes those edges testable.
6. **A limit check is only as good as the lock around it.** `sum + amount <= limit` read without the sender's row lock lets two concurrent transfers each see room for themselves. Taking the sender's `FOR UPDATE` lock first (every transfer from that wallet takes the same lock) turns the read into a reservation. That's why `LimitChecker` documents it as a calling contract.
7. **SQL CHECKs treat NULL as "pass".** `CHECK (x BETWEEN 200 AND 299)` accepts `x = NULL`. Any check on a nullable column needs an explicit `IS NOT NULL` where NULL is wrong, and a test that inserts exactly that row.
8. **Same 404 for every "not found".** Answering "no such number" differently from "number exists but has no wallet" turns a transfer form into a phone-number directory. One message for all of them closes that leak.
