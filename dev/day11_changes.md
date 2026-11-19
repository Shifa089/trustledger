# Changes

## DAY11: Milestone M6 part 3: compliance review of held transfers, concurrency ITs, ADR 0004

**Goal:** finish M6. The parts:
- **High-value hold, approve and reject** (FR-P-3, M6 "Done when" 5, must-pass 7): the compliance queue, plus approve (post, release the hold, complete) and reject (release the hold). COMPLIANCE_OFFICER only.
- **`TransferConcurrencyIT` and `DeadlockFreedomIT`** (must-pass 4 and 5): ExecutorService + CountDownLatch, with a 60 s timeout.
- **`docs/adr/0004-pessimistic-locking.md`**.

Branch: `feature/m6-payments-part3` (created from `master`). **Not committed yet.**

Decisions approved in the plan:
1. **The hold path is extracted from `transfer()`**. The extraction diff was shown and approved before any code was written. Step 5's body is now one call, `held.hold(transfer, now)`. Nothing else in `transfer()` changed. Creating a hold and releasing one now live in one class, `HeldTransferService`.
2. **Approval re-checks the world.** Both wallets must still be ACTIVE (a frozen one → 409 `WALLET_FROZEN`), and the receiver's balance cap is checked again (→ 422 `LIMIT_EXCEEDED`), because money may have arrived while the transfer waited. Rejection works on a frozen wallet.

---

### 1. How to verify Day 11

Run these from `dev/`. Start **Docker Desktop** first. One command per cell.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=HeldTransferServiceTest,TransferEventsTest,ModularityTests"` | Unit tests: the decision order (lock transfer → lock wallets → check → post → release → debit → credit → publish); nothing touched when not held; the hold path still publishes nothing. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=HeldTransferServiceIT,ComplianceTransferControllerIT"` | Approve → posted; reject → hold released; decisions are final; frozen wallet and receiver cap at approval; 20 concurrent decisions → exactly one; HTTP roles, 404, 409, the queue. |
| 4 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=TransferConcurrencyIT,DeadlockFreedomIT"` | 100 concurrent → exactly 50; A↔B 200 times → zero deadlocks; the control test proves a real deadlock *is* detected. |
| 5a | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak localstack` | Starts the local infrastructure. |
| 5b | `./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"` (in a **second** terminal) | Applies `V12` and starts the app with the compliance endpoints. |
| 6 | Open `http/transfers.http` and run it top to bottom | New section at the end: two held transfers, queue, approve, approve again (409), reject, customer tries to approve (403). customer1 needs ₹1,00,000 funded (see the file's header). |

**Results:** `./mvnw clean verify` is **BUILD SUCCESS**: 736 unit tests and 503 integration tests, all green. Details in section 5.

---

### 2. What changed and why

#### The approved diff in `transfer()`

```diff
         if (amount.isGreaterThanOrEqualTo(senderLimits.highValueThreshold())) {
-            wallets.hold(senderId, amount);
-            transfer.holdForReview(now);
-            Transfer held = transfers.save(transfer);
-            log.info("Transfer {} held for review", held.getId());
-            return TransferResult.of(held);
+            return held.hold(transfer, now);
         }
```
Plus the new constructor argument `HeldTransferService held`. Outside `transfer()`, one more change: `requireActive` went from `private static` to package-private `static`, so `approve` uses the same FROZEN/CLOSED → error-code mapping.

#### Payments module

| File | Change | Why |
|---|---|---|
| `application/HeldTransferService` | **`hold`** (`MANDATORY`: only inside `transfer()`'s transaction and locks). **`approve`**: lock the transfer row, lock both wallets (ID order), require ACTIVE, re-check the receiver cap, **post**, release the hold, debit, credit, `transfer.approve(officer, entry, now)`, publish `TransferCompleted`. **`reject`**: lock the transfer, lock the sender, release the hold, `transfer.reject(officer, now)`. **`queue`**: by status, oldest first, page size 1 to 100. | FR-P-3, SPEC §4 step 5, §5. A transfer that isn't HELD gets 409 **before** any wallet is touched, so a late second decision can't release a hold twice. |
| `application/HeldTransferView`, `HeldTransferPage` | What the officer sees: IDs, amount, status, created, reviewer and time. **No note.** | No personal data in the queue (same rule as the KYC queue). |
| `domain/TransferRepository` + `infrastructure/JpaTransferRepository` | `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), `findByStatus(status, pageable)`. | Two officers queue on the transfer row. Lock order: transfer, then wallets (ADR 0004). |
| `web/ComplianceTransferController` | `GET /compliance/transfers?status=HELD_FOR_REVIEW&page&size`, `POST /compliance/transfers/{id}/approve`, `/reject`. Class-level `@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")`. Reviewer is taken from the token. Amounts are decimal strings. | SPEC §5. `SecurityConfig` already maps `/api/*/compliance/**` to OFFICER, so there are two layers. |
| `V12__payments_index_transfers_status.sql` | Index `(status, created_at, id)`. | Serves the queue's filter **and** its sort. (The plan said partial index on HELD only; a full one also serves `?status=REJECTED`.) |
| `package-info.java` | Mentions the review flow. | |

#### Docs and manual requests

| File | Change |
|---|---|
| `docs/adr/0004-pessimistic-locking.md` | Context (lost update, deadlock, concurrent decisions); options (optimistic + retry, SERIALIZABLE, `FOR UPDATE` in order, single writer); decision; consequences, including both persistence-context pitfalls (Day 10 and today). |
| `http/transfers.http` | Compliance section: queue, approve, 409 on re-approve, reject, 403 for a customer. |

#### Tests

| File | What it proves |
|---|---|
| `HeldTransferServiceTest` (7, unit) | Approve order: `findByIdForUpdate` → `lockForUpdate(sender, receiver)` → `checkReceiverCap` → `post` → `releaseHold` → `debit` → `credit` → exactly one `TransferCompleted` with the fixed Clock time. Reject: release only, **no** post, debit, credit or event. Not held / unknown → **no wallet interaction at all**. Frozen wallet and cap refusal → nothing released, still HELD. Page bounds. |
| `HeldTransferServiceIT` (10) | **₹50,000 held → approve:** balance −50k, held 0, receiver +50k, both ledger balances agree, one journal entry, one event. **Reject:** balance unchanged, held 0, no journal entry, no event. **A rejected transfer frees the daily limit** (two holds use up ₹1,00,000; a third is refused; after one reject, it passes). **Decisions are final** (all four second decisions → 409, held still 0, not released twice). An ordinary completed transfer is not reviewable. Unknown → 404. **Frozen sender:** approve 409, still held, then reject releases. Frozen receiver: approve 409. **Receiver cap re-checked** (₹1,40,000 + ₹50,000 fits at hold time; after a ₹20,000 top-up it doesn't → 422, still held). **20 concurrent approve/reject → exactly 1 decision, 19 × 409**, hold released once, ledger = cache. |
| `ComplianceTransferControllerIT` (7) | Queue: our transfer found by walking pages, every item HELD, **oldest first across pages**, amount `"50000.00"`, **no `note` key**. Approve 200 with `reviewedBy` = the officer's own user ID. Reject 200. Second decision 409 `INVALID_STATE_TRANSITION`. Unknown 404. **A customer can't decide, even on their own transfer** (403, still held). Page size 0 / 101 and page −1 → 400. |
| `ConcurrentRun` (test support) | ExecutorService (16 threads) + one start `CountDownLatch`: submit all, then open. A **60 s overall deadline**; a timeout fails with "probable deadlock or unbounded lock wait". An unexpected task exception fails the run. |
| `TransferConcurrencyIT` (1) | The 100-concurrent test, now on `ConcurrentRun`. |
| `DeadlockFreedomIT` (2, new; the A↔B test moved here) | **A→B / B→A 200 times:** each failure is classified (a `PessimisticLockingFailureException` or SQLSTATE `40P01` anywhere in the cause chain = DEADLOCK); asserts **zero** deadlocks, 200 completed, totals preserved in cache and ledger. **Control:** two raw JDBC transactions lock the same two rows in *opposite* order and meet at a latch → exactly one gets `40P01`. That proves a green main test can't mean "deadlock went unnoticed". |
| `SecurityMatrixIT` (+14) | `GET /compliance/transfers`: 401 / 403 customer / 200 officer / 403 admin / 403 auditor. `approve` and `reject`: 401 / 403 customer / **404 officer** (unknown ID: past security) / 403 admin (and auditor for approve). |
| `TransferEventsTest` | Builds a real `HeldTransferService` on the same mocks, so the existing hold test still sees `wallets.hold` and no posting. |
| `PaymentsFixtures` | `transferStatus(id)`. |

---

### 3. Machine setup done on Day 11 (not in Git)

- Branch `feature/m6-payments-part3` created from `master`. **Nothing committed yet.**
- No new dependencies, no `.env` changes, no new Docker images. One new migration (`V12`).

### 4. Deferred

- **The wallet-module lock-mode trap (found today, see 5).** `WalletLocks.isLockedForUpdate` asks Hibernate for the entity's lock mode. After Hibernate flushes a changed wallet, that mode reads as `WRITE`, so the next `debit`/`credit` on the same wallet in the same transaction is refused as "not locked", even though the row lock is still held. Today's code avoids it by ordering (post first). A real fix belongs in the wallet module: remember the locked IDs per transaction, instead of asking the persistence context. **M7 (top-up credit after a webhook) and any reversal flow will meet this.** Proposed for the start of M7.
- **`lock_timeout`** (ADR 0004): set one (e.g. 5 s) in M13 hardening.
- **Audit events for approve/reject** (`reviewed_by` is stored now; the hash-chained audit trail is M10).
- **The crash window and FAILED transfers** from Day 10: still open.

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS**.

- **Unit tests:** core-app **736/736**, up from 729 (+7, `HeldTransferServiceTest`). `ModularityTests` green.
- **Integration tests:** core-app **503/503**, up from 471 (+32: `HeldTransferServiceIT` 10, `ComplianceTransferControllerIT` 7, `DeadlockFreedomIT` 2, `SecurityMatrixIT` +14, `TransferConcurrencyIT` −1 because its A↔B test moved).
- **AML service:** 1/1.
- **JaCoCo (core-app):** 96.6% instructions (456 of 13,308 missed), 88.5% branches (107 of 928 missed). `payments`: 94.7% / 85.2%. **`HeldTransferService`: 100% / 100%.**
- **Timing:** `DeadlockFreedomIT` about 5 s (both tests; the control spends about 1 s in PostgreSQL's `deadlock_timeout`), `TransferConcurrencyIT` about 1 s, `HeldTransferServiceIT` under 1 s.

**Fix found while testing:**
1. **`approve` first released the hold, then posted.** On the first run, 2 `HeldTransferServiceIT` and 2 `ComplianceTransferControllerIT` tests failed with `IllegalStateException: Wallet … must be locked with lockForUpdate in this transaction first`, on the **debit**. Cause: `releaseHold` makes the wallet dirty; `ledger.post` runs queries, so Hibernate auto-flushes first; after the flush the entity's lock mode reads `WRITE`, not `PESSIMISTIC_WRITE`; the wallet guard refuses the debit. Fix: post first, then release, debit and credit, the same order as `transfer()`. Commented in code and recorded in ADR 0004. The root cause in the wallet module is Deferred.

---

### 6. What to learn from Day 11

1. **A lock order is a global contract, not a local habit.** `transfer()` locks wallets only. `approve()` locks the transfer row, then wallets. Those can't form a cycle because `transfer()` never locks an existing transfer row. Whenever you add a code path that locks something, ask: "is there any path that takes these two locks in the other order?"
2. **Lock the thing being decided, then re-check its state under the lock.** Two officers clicking at once is the same race as two transfers on one wallet. `findByIdForUpdate`, then "is it still HELD?" turns the race into a queue. The second officer gets a clean 409, not a double posting or a double release.
3. **A decision made later must re-validate.** At hold time the receiver had room. Twenty minutes later it might not. Anything checked at creation that can change (status, cap) is checked again at approval, under the lock.
4. **Prove your detector works.** A "no deadlock" test that passes could also mean "deadlocks weren't detected". The control test creates a real deadlock on purpose and asserts it is seen. Use this pattern whenever a test asserts that something *didn't* happen.
5. **The ORM's view of a lock is not the database's.** The row lock was held the whole time; only Hibernate's bookkeeping changed after a flush. When code asks the ORM "is this locked?", it inherits the ORM's quirks. Order operations so that reads that trigger a flush come before writes, or keep your own record of what you locked.
6. **Keep holds and releases next to each other.** Putting `hold`, `approve` and `reject` in one class makes the invariant "every hold is released exactly once" checkable by reading one file, and testable by one IT class.
