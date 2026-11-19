# Changes

## DAY10: Milestone M6 part 2: TransferService, POST/GET /transfers, TransferCompleted

**Goal:** the P2P transfer itself (SPEC §4 "Transfer flow", FR-P-1 to FR-P-3) on top of the Day 9 building blocks. The parts:
- **`TransferService.transfer()`**: steps 2 to 7 of the flow in one database transaction.
- **`POST /api/v1/transfers`** (`@Idempotent`) and **`GET /api/v1/transfers/{id}`** (sender or receiver only).
- **`TransferCompleted`** event, published inside the money transaction.
- **A real bug fixed in the wallet module** (lost-update risk in `lockForUpdate`), found by the new concurrency test.

Branch: `feature/m6-payments-part2` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **Claude implements the body of `transfer()`.** You chose this instead of the SPEC's "write it yourself". Section 2 maps each of the 8 steps to the line that does it, so you can review it step by step.
2. **Concurrency and hold tests are added today.** That covers M6 "Done when" 2, 3 and the hold half of 5. Approve/reject (the other half of 5) needs the compliance review endpoints and moves to part 3.
3. **The crash window stays documented, not fixed** (see Deferred).

---

### 1. How to verify Day 10

Run these from `dev/`. Start **Docker Desktop** first. Every cell holds exactly **one** command; paste them one at a time.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=TransferEventsTest,ModularityTests"` | Unit tests: exactly one `TransferCompleted`, none for a hold or a refusal, order lock → post → publish; payments → ledger only through `LedgerApi`. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=TransferServiceIT,TransferControllerIT"` | Happy paths, every rejection rule with "nothing moved", the hold, the HTTP key/replay/422/BOLA rules. |
| 4 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=TransferConcurrencyIT,WalletApiIT"` | **100 concurrent transfers with balance for 50 → exactly 50**; **A↔B 200 times → no deadlock**; the lost-update regression test. |
| 5a | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak localstack` | Starts the local infrastructure. |
| 5b | `./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"` (in a **second** terminal) | No new migration; the app starts with the new endpoints. |
| 6 | Open `http/transfers.http` in IntelliJ and run it top to bottom | Manual tour: 201, replay, 422, 400, hold, GET as sender/receiver, 403 for an officer. Until M7 there are no top-ups, so customer1 needs funds or the transfer returns 422 `INSUFFICIENT_FUNDS` (see the file's header). |

**Results:** `./mvnw clean verify` is **BUILD SUCCESS**: 729 unit tests and 471 integration tests green. Details in section 5.

---

### 2. What changed and why

#### The 8 steps and where they live

| Step (SPEC §4) | Where | What happens |
|---|---|---|
| 1. Idempotency check | `IdempotencyInterceptor.preHandle` → `IdempotencyService.claim` (`REQUIRES_NEW`), triggered by `@Idempotent` on `TransferController.create` | Done before `transfer()` is called. Replay / 409 / 422 never reach the service. |
| 2. Validate | `transfer()`: `walletApi.findByUser(senderUserId)` (no wallet → 403 `KYC_REQUIRED`), `recipientResolver.resolve(...)` (404 / own wallet 400), `Transfer.initiate(...)` (amount > 0, INR, not self, note); amount format by `@ValidAmount` + `Money.parse` in the controller | The command has **no sender wallet ID**: the sender is always the caller's own wallet, so POST can't be used for BOLA. |
| 3. Lock in ID order | `walletApi.lockForUpdate(List.of(sender, receiver))` | `SELECT ... ORDER BY id FOR UPDATE`: PostgreSQL's order, the same in every transaction. Returns snapshots read **under** the locks. |
| 4. Checks under lock | `requireActive` (FROZEN → 409 `WALLET_FROZEN`, CLOSED → 409 `INVALID_STATE_TRANSITION`) for both wallets; `limitChecker.checkSender` (tier P2P, daily, monthly); `available < amount` → 422 `INSUFFICIENT_FUNDS`; `limitChecker.checkReceiverCap` | All read from the locked snapshots, so they can't change before commit. |
| 5. High value | `amount ≥ highValueThreshold` → `walletApi.hold`, `transfer.holdForReview`, save, return | No posting, no event. Approval/rejection is part 3. |
| 6. Post | `transfers.save` (assigns the UUIDv7), `ledgerApi.post(postingFor(transfer))`, `walletApi.debit`, `walletApi.credit`, `transfer.complete(entryId)` | The posting debits `WALLET:sender` and credits `WALLET:receiver` (both liabilities), `referenceType TRANSFER`, `referenceId` = transfer ID, so the ledger's unique key forbids a double posting. |
| 7. Publish | `events.publishEvent(new TransferCompleted(...))` | Spring Modulith writes it to `event_publication` in the same transaction. |
| 8. Commit, store response | The `@Transactional` proxy commits; `IdempotencyInterceptor.afterCompletion` stores the 201 body | Only 2xx is stored (Day 9 decision 2). |

Any refusal throws, the transaction rolls back, and nothing is persisted. Refused attempts are not recorded as FAILED (Deferred).

#### Payments module

| File | Change | Why |
|---|---|---|
| `TransferCompleted` (base package) | Event record: transfer, sender, receiver, amount, journal entry, completedAt. IDs and money only. | Step 7. The base package is the module's public API, so AML/notifications (M8) can listen. |
| `application/TransferCommand`, `TransferResult` | Command in, result out. `TransferCommand.toString()` masks the note; `TransferRecipient` already masks the mobile. | The entity never leaves the module. |
| `application/TransferService` | Steps 2 to 7, `@Transactional`. Collaborators: `WalletApi`, `LedgerApi`, `LimitChecker`, `RecipientResolver`, `TransferRepository`, `ApplicationEventPublisher`, `Clock`. `now` is taken once from the Clock. | SPEC §4. |
| `application/TransferQueryService` | `get(callerUserId, transferId)`: the transfer only if `transfer.involves(callerWallet)`, else the **same** 404 as an unknown ID. | BOLA, and no way to find out whether an ID exists. |
| `web/TransferController` | `POST /transfers` (`@Idempotent`, 201) and `GET /transfers/{id}`, both `hasRole('CUSTOMER')`. `TransferRequest` (`recipientMobile` or `recipientWalletId`, `amount` as decimal string, `note` ≤ 140), masked `toString()`. `TransferResponse` with the amount as a decimal string. | SPEC §5, §6. A held transfer is also 201: it exists, and `status` says HELD_FOR_REVIEW. No `Location` header, because a replay stores only status and body and must look identical. |
| `web/Callers` | Payments copy of the token → user ID helper. | Same pattern as wallet and kyc. |
| `web/PaymentsStubController` | Transfer stub removed; the top-up stub stays until M7. | |
| `package-info.java` | Depends on identity, wallet **and ledger**; public API = `TransferCompleted`. | `ModularityTests` green. |

#### Wallet module: the bug the concurrency test found

| File | Change | Why |
|---|---|---|
| `domain/WalletLocks` + `infrastructure/JpaWalletLocks` | New `evictUnlocked(ids)`: detaches wallets the transaction loaded **without** a lock (uses `getReference`, so no query). | See below. |
| `application/WalletApiImpl.lockForUpdate` | Calls `evictUnlocked` before the `FOR UPDATE` query. | |

**What happened:** `transfer()` reads the sender (`findByUser`) and the receiver (`findById`) *unlocked*, in the same transaction, before locking them. Those reads put the `Wallet` entities into the persistence context. When another transfer committed in between, the `SELECT ... FOR UPDATE` returned a **newer version** of the same row. Hibernate 7 refused to mix the two: `ObjectOptimisticLockingFailureException: Query result contains conflicting version of entity already held in persistence context`. The 100-concurrent test failed on the first run.

**Why it matters more than a failed request:** without the `@Version` column, Hibernate would have **silently returned the stale entity** from the persistence context. The lock would have been taken, but the debit would have been computed from the old balance: a lost update in a wallet. The fix belongs in `WalletApi`, because its contract offers `findById` "before the locks are taken", so every future caller (top-ups in M7, approvals in part 3) would hit it.

#### Shared, configuration, docs

| File | Change | Why |
|---|---|---|
| `SecurityConfig` | New rule `GET /api/*/transfers/*`, `/api/*/topups/*` → CUSTOMER. | CLAUDE.md: every endpoint has an explicit role rule. Before, the GET only fell under `/api/**` authenticated, so an officer reached the controller and was stopped only by `@PreAuthorize`. |
| `RateLimitIT` (test) | Uses the `/topups` stub instead of `/transfers`. | The test needs an endpoint in the MONEY bucket that answers 501 without a body; `/transfers` is real now (no key → 400). |
| `SecurityMatrixIT` (test) | `POST /transfers` as CUSTOMER now expects 400 (was the stub's 501); added POST as COMPLIANCE_OFFICER → 403 and six rows for `GET /transfers/{id}` (401 anonymous, 403 for no roles / officer / admin / auditor, 404 for a customer). | The role matrix must list every endpoint. |
| `docs/SPEC.md` §5 | POST: 201 with COMPLETED or HELD_FOR_REVIEW; GET: anyone else gets the same 404. | Keep the spec the source of truth. |
| `http/transfers.http` | Manual requests: create, replay, missing key, hold, bad amount, GET as sender/receiver/officer. | |

#### Tests

| File | What it proves |
|---|---|
| `TransferEventsTest` (8, unit, Mockito) | Completed → **exactly one** `TransferCompleted` with the right IDs, amount, journal entry and `completedAt` from the fixed Clock. Order **lock → limits → post → debit → credit → publish**. Posting: P2P_TRANSFER, `TRANSFER`/transfer ID, debit sender, credit receiver. **No event** for a hold, a limit refusal, a frozen receiver, insufficient funds, or a ledger failure. |
| `TransferServiceIT` (17) | By wallet ID and by mobile: both cached balances **and** both ledger balances move, one journal entry, COMPLETED with its journal ID. Whole balance can be spent. **₹50,000 → HELD_FOR_REVIEW**, held up, balance unchanged, no journal entry; ₹49,999.99 completes; held money isn't available. **Each rejection rule**, each asserting nothing moved (both wallets, both ledger balances, transfer count): frozen sender, frozen receiver, MIN tier, no wallet, insufficient funds, **daily limit (₹0.02 lands exactly on it and passes, ₹0.03 is refused)**, monthly limit, receiver cap, own wallet (by ID and by mobile), unknown recipient (by ID and by mobile), zero amount. |
| `TransferConcurrencyIT` (2) | **M6 "Done when" 2:** 100 concurrent ₹100 transfers from a ₹5,000 wallet → exactly 50 COMPLETED and 50 `INSUFFICIENT_FUNDS`, balance 0 (never negative), 50 rows, ledger equals cache. **"Done when" 3:** A→B and B→A ₹1, 200 times at once → all 200 complete (a deadlock would surface as an exception), both balances back where they started, ledger total preserved. All tasks wait on one `CountDownLatch` so they really collide. |
| `TransferControllerIT` (20) | Created 201 with the decimal-string body; by mobile. **Missing key → 400, nothing moved. Same key twice → identical body, `Idempotent-Replayed: true`, one transfer, debited once. Same key, other body → 422.** A refusal releases the key, so the same key succeeds after funding. Bad amounts (`10.001`, `-1.00`, `abc`, `0`, `1e3`, empty) → 400; both/neither recipient → 400; note > 140 → 400. Officer POST → 403; unauthenticated → 401. **BOLA:** sender and receiver read it (200); a stranger gets 404 with **the same detail as an unknown ID**; a user without a wallet → 404; an officer → 403. |
| `WalletApiIT` (+1) | Regression: an unlocked read, then another transaction commits a credit, then `lockForUpdate` returns the **committed** balance and the debit uses it. |
| `PaymentsFixtures` (test support) | Customers with subject, user, wallet and mobile; **ledger-consistent funding** (TOPUP posting + credit in one transaction, as Day 9 asked for), freeze, cache and ledger balances. |

---

### 3. Machine setup done on Day 10 (not in Git)

- **Branch** `feature/m6-payments-part2` created from `master`, committed and merged back into `master` (`--no-ff`). Not pushed.
- **No new dependencies**, no migration, no `.env` changes, no new Docker images. Docker Desktop must be running for the ITs.

### 4. Deferred

- **Part 3 (Day 11):** `GET /compliance/transfers?status=HELD_FOR_REVIEW`, approve (post + release the hold + publish `TransferCompleted`) and reject (release the hold), with "approver must be a compliance officer". Their "Done when" 5 tests (approve → posted, reject → hold released) come with them.
- **The crash window** (decision 3): the business commit succeeds, then the process dies before `complete()` stores the response. The key stays IN_PROGRESS (409) until it expires; the money moved once. Fix option: store the transfer ID with the key in the same transaction, so a retry can rebuild the response.
- **FAILED transfers:** a refusal rolls back, so nothing is recorded. Recording refused attempts in a separate transaction (useful for AML velocity rules) is still open.
- **`TransferCompletedV1`** in `event-contracts` and the Kafka externalisation: M8.

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS**.

- **Unit tests:** core-app **729/729 green**, up from 721 (+8, `TransferEventsTest`). `ModularityTests` is green: payments uses `IdentityApi`, `WalletApi` and `LedgerApi` only.
- **Integration tests:** core-app **471/471 green**, up from 424 (+47: `TransferServiceIT` 17, `TransferControllerIT` 20, `TransferConcurrencyIT` 2, `WalletApiIT` +1, `SecurityMatrixIT` +7). Nothing skipped (the monthly-limit test skips only on the 1st of a month in IST).
- **AML service:** the IT passed (1/1).
- **JaCoCo (core-app):** 96.4% of instructions (456 of 12,817 missed) and 88.3% of branches (107 of 916 missed). The `payments` packages: 93.6% instructions, 84.1% branches; `TransferService` misses only the CLOSED-wallet branch (no endpoint closes a wallet yet, so no test can reach it through real services).
- **Timing:** the 100-concurrent test takes about 2 s, the A↔B test about 11 s.

**Fixes found while testing:**
1. **The lost-update risk in `WalletApi.lockForUpdate`** (section 2). Found by the first run of `TransferConcurrencyIT`: both concurrency tests failed with `ObjectOptimisticLockingFailureException`. They passed after the fix, with a regression test in `WalletApiIT`.
2. **`TransferConcurrencyIT` itself:** the first version released the start latch *before* submitting the tasks, so they didn't really start together. Fixed: submit everything, then `countDown()`.
3. **`SecurityMatrixIT`** still expected the 501 stub for `POST /transfers` (CUSTOMER). The first full build caught it; the matrix now covers both transfer endpoints.

---

### 6. What to learn from Day 10

1. **Ordered locking turns a cycle into a queue.** A deadlock needs two transactions each holding a lock the other wants. If A→B locks A then B, and B→A locks B then A, that cycle is possible. If *both* lock the smaller ID first, whoever gets the first lock also gets the second; the other waits on the first and never holds anything the winner needs. The order must be the **database's** order (`ORDER BY id`), not Java's `UUID.compareTo`, which compares signed longs and disagrees with PostgreSQL.
2. **A lock protects a decision only if the decision reads the locked row.** The balance, status and limit checks use the snapshots `lockForUpdate` returns, never the earlier unlocked reads. Read → lock → decide on what you read *under* the lock.
3. **The persistence context is a cache, and caches go stale.** Today's bug: an entity read early in the transaction is reused by later queries for the same ID. A `FOR UPDATE` lock on the row doesn't refresh the Java object. The `@Version` column turned a silent lost update into a loud exception. That's why every aggregate here has `@Version`, even ones that are pessimistically locked.
4. **Concurrency tests find bugs that unit tests can't.** All 17 functional ITs passed on the first run. Only 100 transactions colliding on one row exposed the stale snapshot. Use a `CountDownLatch` so threads start together, and assert outcomes from the business view (50 successes, balance 0, ledger equals cache), not timing.
5. **Steps 1 and 8 live outside the transaction on purpose.** The idempotency claim must commit *before* the work (so a duplicate sees it) and survive a rollback. The response can only be stored *after* the commit (otherwise a replay could return a 201 for money that never moved). Neither can be inside the business transaction, so they sit in the interceptor around it.
6. **Publish inside, deliver after.** `publishEvent` inside the transaction lets Spring Modulith write the event to `event_publication` atomically with the money. Kafka delivery (M8) happens after commit from that table, so there is never an event for a rolled-back transfer, and never a committed transfer without its event.
7. **Design BOLA out instead of checking for it.** POST has no sender field at all: the sender is the caller's own wallet. GET can't avoid an ID, so it checks `involves(callerWallet)` and answers the same 404 for "not yours" and "doesn't exist".
