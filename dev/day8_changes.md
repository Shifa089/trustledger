# Changes

## DAY8: Milestone M5 part 2: `post()`, reversal and balance tests, reconciliation with ShedLock, ADR 0003

**Goal:** finish M5. The parts:
- **`LedgerPostingService.post()`** written, following the 6 steps in `day7_changes.md` section 6. Every red test from Day 7 is now green.
- **Reversal (FR-L-3)** and **`balanceOf`** already existed. They got the missing tests: concurrent double reversal, multi-line reversal, the platform account restored, balances through `post()`, a wallet account below zero.
- **`LedgerApi.balancesOf(codes)`**: many balances in two queries, for reconciliation.
- **Reconciliation (FR-L-4)**:
  - a nightly `@Scheduled` job (cron from config) with **ShedLock**, comparing each wallet's `balance_minor` with its ledger balance;
  - mismatches are logged (WARN) and counted in `trustledger.reconciliation.mismatches{reason}`;
  - `POST /api/v1/admin/reconciliation/run` for ADMIN.
- **ADR 0003** (money as minor units) and **ADR 0013** (ShedLock).

Branch: `feature/m5-ledger-part2` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **Reconciliation lives in the wallet module, not the ledger.**
   - The ledger must depend on no business module, so it can't read wallets.
   - The wallet owns the cached balance and already depends on the ledger.
   - There is no `reporting` module yet.
   - SPEC §3 is updated: the `ledger/` line now says "balances" and `wallet/` says "reconciliation (FR-L-4)".
2. **ShedLock is added** (ADR 0013), with its table in a new `shared` schema (`shared.shedlock`). It's the `shared` module's first table.
3. **One lock, two triggers.**
   - The cron trigger and the admin endpoint both take the lock through `LockingTaskExecutor` (no `@SchedulerLock`).
   - So they share one code path and one lock name, `wallet-reconciliation`.
   - A manual run while a run is in progress returns **409 `REQUEST_IN_PROGRESS`**, an existing error code.
4. **Each page of wallets is checked in one read-only REPEATABLE READ transaction.** The wallet balances and their ledger balances then come from the same snapshot. This prevents false mismatches once M6 transfers update both sides at once.
5. **Nothing is corrected automatically.** A mismatch is a bug. It is reported and counted; the fix is a reviewed correction.

---

### 1. How to verify Day 8

Run these from `dev/`. Start **Docker Desktop** first. Every cell holds exactly **one** command; paste them one at a time.

> **PowerShell:** put every `-D...` argument that contains a dot in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build: **green** again (Day 7 was red on purpose). |
| 2 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=LedgerPostingServiceIT,LedgerReversalIT,LedgerAccountServiceIT"` | `post()`, reversal and balances. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=ReconciliationIT,AdminReconciliationControllerIT"` | Reconciliation, including the M5 "Done when": **a deliberately corrupted cached balance is detected**. |
| 4 | `./mvnw -pl core-app test "-Dtest=ModularityTests"` | The ledger still depends on no business module; wallet → ledger only. |
| 5a | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak localstack` | Starts the local infrastructure. |
| 5b | `./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"` (in a **second** terminal) | Flyway applies **V9** (`shared.shedlock`). Wait for `Started TrustLedgerApplication`. |
| 6 | `http/admin.http` → "admin1: run reconciliation now" (after "admin1: token" in `auth.http`) | 200 with the report. Wallets from Day 6 (before V7) show up as `UNLINKED`. |
| 7 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "select name, lock_until, locked_by from shared.shedlock"` | One `wallet-reconciliation` row: the lock the run took. |

**Results:** `./mvnw clean verify` is **BUILD SUCCESS**: 633 unit tests and 382 integration tests green. Details in section 5.

---

### 2. What changed and why

#### `LedgerPostingService.post()` (FR-L-1)

| Step | What it does | Why |
|---|---|---|
| 1. Shape | ≥ 2 lines, no zero amount, one currency, debits = credits (summed with `Math.addExact`) → `VALIDATION_FAILED` | Cheap checks first, no database. A huge amount throws instead of wrapping around. |
| 2. Duplicate | `existsByReferenceTypeAndReferenceIdAndEntryType` → `DUPLICATE_POSTING` | The friendly path for an ordinary retry. |
| 3. Accounts | One `findAllByCodeIn(distinct codes)`; a missing code → `NOT_FOUND`; another currency → `VALIDATION_FAILED` | One query for all accounts, not one per line. |
| 4. Build | `JournalEntry.start(..., clock.instant())`, then `addLine` in the command's order | Line numbers 1..n follow the command (the tests check `line_no`). |
| 5. Save | `saveAndFlush`; `DataIntegrityViolationException` → `DUPLICATE_POSTING` | A racing transaction that passed step 2 loses on the unique key here, and gets a business error instead of a raw SQL error. |
| 6. Return | The entry ID | At COMMIT the deferred trigger checks the balance once more. |

Everything is validated before anything is written, so a refused posting leaves no rows.

#### Ledger module

| File | Change | Why |
|---|---|---|
| `LedgerApi.balancesOf(Collection<String>)` | New. Returns `Map<code, AccountBalance>`; unknown codes are absent instead of failing the call | Reconciliation checks hundreds of wallets per page. `balanceOf` per wallet would be 3 queries each. |
| `LedgerAccountService.balancesOf` | Accounts in one query, then debit and credit totals for all of them in one grouped query; normal side applied per account | 2 queries per page, whatever the page size. An account with no lines → 0. |
| `domain/AccountSideTotal`, `JournalLineRepository.sumByAccountAndDirection` | JPQL `select new AccountSideTotal(accountId, direction, sum(amountMinor)) ... group by accountId, direction` | Uses the existing `ix_journal_lines_account_entry` index. |

#### Wallet module: reconciliation (FR-L-4)

| File | Change | Why |
|---|---|---|
| `application/ReconciliationProperties` | `trustledger.reconciliation.*`: `cron` (`0 0 2 * * *`), `zone` (`Asia/Kolkata`), `batch-size` (500), `max-listed-mismatches` (1000), `lock-at-most-for` (30m), `lock-at-least-for` (1m). Startup fails on an invalid cron, a batch outside 1-5000, or inconsistent lock times. | CLAUDE.md: config via `@ConfigurationProperties` records, no hard-coded values. A typo in the cron would otherwise silently disable FR-L-4. |
| `domain/WalletBalanceRow`, `WalletRepository.balancesAfter(afterId, limit)` | Keyset paging (`where id > :afterId order by id`), reading only id, balance, currency and ledger link | No OFFSET (a page costs the same however deep), and no entity loading. |
| `application/ReconciliationService` | Pages through every wallet. Per page: one read-only REPEATABLE READ transaction reads the wallets and `LedgerApi.balancesOf` for them. Classification: **BALANCE** (amount or currency differs) and **UNLINKED** (no ledger link, or the linked account is missing). Each mismatch is logged at WARN (wallet ID and amounts only) and counted. The run ends with an INFO "clean" or a WARN `RECONCILIATION ALERT` summary. | See decisions 4 and 5. A WARN with a fixed prefix is the "log alert" SPEC asks for, until Grafana alerts arrive in M12. |
| `application/ReconciliationReport` | `walletsChecked`, `mismatches`, `unlinked`, `listed` (capped), `truncated`, `startedAt`, `finishedAt`. Amounts as decimal strings; a negative ledger balance keeps its sign (`"-5.00"`). | CLAUDE.md: API amounts are decimal strings. The cap keeps the response bounded if something goes badly wrong; every mismatch is still counted and logged. |
| `application/ReconciliationJob` | `@Scheduled(cron, zone)` → `nightly()`, which skips quietly if another instance holds the lock. `runNow()` for the endpoint: `lockAtLeastFor = 0`, 409 when locked. | See decision 3. |
| `web/AdminReconciliationController` | `POST /admin/reconciliation/run` (version 1), `@PreAuthorize("hasRole('ADMIN')")` on top of the existing `/api/*/admin/**` → ADMIN rule. Logs which admin (user ID) asked. | SPEC §5. Two layers of role checks, as for the compliance endpoints. |
| `application/WalletConfig` | Registers `ReconciliationProperties` | |

#### Shared, database and configuration

| File | Change | Why |
|---|---|---|
| `V9__shared_create_shedlock.sql` | `CREATE SCHEMA shared` + `shared.shedlock(name pk, lock_until, locked_at, locked_by)` | ShedLock's table. V1 gave `shared` no schema because it had no tables. |
| `shared/config/SchedulingConfig` | `JdbcTemplateLockProvider` on `shared.shedlock` **using DB time**, a `LockingTaskExecutor` bean, and `@EnableScheduling` behind `trustledger.scheduling.enabled` | DB time: instances with skewed clocks still agree on who holds the lock. |
| `application.yml` | `trustledger.scheduling.enabled: true` and the `reconciliation` block | |
| `src/test/resources/config/application.yml` | `trustledger.scheduling.enabled: false` | A timed run can't interfere with an IT; the tests call the job themselves. |
| `pom.xml` (parent) | `shedlock.version` 7.10.1 + `shedlock-bom` import | Released 2026-09-08, built against Spring 7.0.9 / Boot 4.1. 7.11.0 was under two weeks old. |
| `core-app/pom.xml` | `shedlock-spring`, `shedlock-provider-jdbc-template` | |

#### Docs and requests

| File | Change |
|---|---|
| `docs/adr/0003-money-as-minor-units.md` | New: `double`, `BigDecimal`, JSR-354 and `long` minor units compared; why `Money(long, Currency)`, `bigint`, decimal strings, `addExact`. |
| `docs/adr/0013-shedlock-for-scheduled-jobs.md` | New: ShedLock, a hand-written advisory lock, a Redis lock, Quartz, and a single scheduler instance compared; DB time, the explicit lock and the lock durations. |
| `CLAUDE.md` | ShedLock (ADR 0013) added to the stack line. |
| `docs/SPEC.md` §3, §4 | Reconciliation listed under `wallet/`; the `shared` schema and the `shared.shedlock` table. |
| `http/admin.http` | New: run reconciliation (ADMIN), read the counter, 403 for a customer, 401 without a token. |

#### Tests

| File | Status | What it proves |
|---|---|---|
| `LedgerPostingServiceIT` (14) | **green** (was 13 red) | Section 2 of `day7_changes.md`, including **8 threads posting the same reference → 1 entry + 7 × `DUPLICATE_POSTING`**. |
| `LedgerReversalIT` (6 → 9) | green (was 2 red) | New: **8 threads reverse the same entry → exactly 1 reversal + 7 × `DUPLICATE_POSTING`**; a 3-line entry is reversed line by line in the same order; the debit-normal clearing account returns exactly to where it was. |
| `LedgerAccountServiceIT` (4 → 8) | green | New, through `post()`: a top-up grows the asset on the debit side and the wallet on the credit side; a wallet account below zero is signed (`-500`) and refused by `toMoney()`; `balancesOf` returns every known account and skips unknown codes; `balancesOf` of nothing is empty. |
| **`ReconciliationIT` (6)** | green | **M5 "Done when": `balance_minor` corrupted by raw SQL → exactly one BALANCE mismatch for that wallet (`"251.00"` vs `"250.00"`), the healthy wallet isn't listed, and the counters go up by exactly the report's numbers.** Also: consistent wallets aren't reported; a wallet without a ledger link → UNLINKED; a held lock → 409, then the run works once released; the lock is a row in `shared.shedlock`; the nightly trigger skips while locked. |
| `AdminReconciliationControllerIT` (4) | green | ADMIN → 200 with the report's JSON shape; lock held → 409 ProblemDetail `REQUEST_IN_PROGRESS`; CUSTOMER, COMPLIANCE_OFFICER and AUDITOR → 403; no token → 401. |
| Unit: `ReconciliationServiceTest` 11, `ReconciliationPropertiesTest` 8, `ReconciliationJobTest` 5, `LedgerAccountServiceTest` +3, `LedgerApiImplTest` (extended) | green | Match / BALANCE / currency / UNLINKED / missing account; keyset paging and stopping on a short page; the list is capped but every mismatch is counted; **each page is read in a read-only REPEATABLE READ transaction**; a negative ledger balance is shown with its sign; config validation and binding; lock times per trigger; 409 when locked; the nightly run skips when locked; failures propagate. |

**Fixes found while testing:** none. Every new test passed on its first run.

---

### 3. Machine setup done on Day 8 (not in Git)

- **Branch** `feature/m5-ledger-part2` created from `master`, committed and merged back into `master` (`--no-ff`). Not pushed.
- **New dependency:** ShedLock 7.10.1 (ADR 0013). No `.env` changes and no new Docker images.

### 4. Deferred

- **Backfill** of ledger accounts for wallets created before V7. Reconciliation now *finds* them (`UNLINKED`). `WalletLedgerLinkService.link` is idempotent, so a one-off admin action can fix them. It is left out on purpose: a reconciliation job that silently "fixes" things would hide the bug that caused them.
- **`GET /admin/ledger/accounts/{code}/balance`** (ADMIN, AUDITOR). The security rule exists; the endpoint comes with M11 reporting.
- **Prometheus and a Grafana alert** on `trustledger_reconciliation_mismatches_total` (M12). Until then: the WARN log and `/actuator/metrics/trustledger.reconciliation.mismatches`.
- **Posting from payments** (top-up and transfer) together with the wallet balance update in one transaction: M6/M7. Until then only tests create ledger postings, which is why some older wallet ITs leave mismatched wallets in the test database (see the `ReconciliationIT` Javadoc).

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS**: `master` is green again.

- **Unit tests:** core-app **633/633 green**, up from 606 (+27). `ModularityTests` is green: the ledger depends on no business module, and wallet → ledger is the only new direction used.
- **Integration tests:** core-app **382/382 green**, up from 365 run on Day 7 (+17). The **15 red tests from Day 7 are all green** (13 in `LedgerPostingServiceIT`, 2 in `LedgerReversalIT`).
- **AML service:** the IT passed (1/1).
- **JaCoCo (core-app):** 97% of instructions (307 of 10,387 missed) and 89% of branches (80 of 730 missed). The report is back because nothing stops the build before the `report` goal any more.

---

### 6. What to learn from Day 8

1. **"Check, then insert" is only safe with a unique key behind it, and you must translate the loser's error.** All 8 threads pass `existsBy...` before anyone commits. PostgreSQL makes the 7 losers wait on the winner's uncommitted unique key, then fails their INSERT. `saveAndFlush` makes that failure happen inside `post()` (not at commit, outside your `try`), where it can become `DUPLICATE_POSTING`. Reversal gets the same protection for free because it goes through `post()`.
2. **Reconciliation compares two numbers, so they must come from the same moment.** Read the wallet balance at 02:00:00.100 and the ledger at 02:00:00.300, and a transfer committed in between looks like missing money. One REPEATABLE READ transaction per page gives both reads one snapshot. Paging keeps each snapshot short, so it doesn't hold back vacuum for an hour.
3. **Keyset paging beats OFFSET for "walk the whole table".** `OFFSET 100000` makes the database read and throw away 100,000 rows. `where id > :last order by id limit 500` jumps straight there through the primary key, and a row inserted mid-run can't shift the pages.
4. **A scheduled job runs on every instance unless something says otherwise.** ShedLock turns "run at 02:00" into "the first instance to claim the row at 02:00 runs it". `lockAtMostFor` is the crash safety net; `lockAtLeastFor` covers instances whose trigger fires a few seconds late; DB time covers clock skew.
5. **Detect, don't auto-correct.** It's tempting to make reconciliation overwrite the cached balance with the ledger's. But a mismatch means some code path updated one side without the other. Silently fixing the symptom hides the bug and could also hide fraud. Count it, alert on it, and correct it with a reviewed, auditable entry.
6. **Integer minor units make money boring, and boring is what you want** (ADR 0003). `long` paise give exact equality (what reconciliation and the balance trigger need), fast `bigint` sums, and `addExact` turns the impossible overflow into an exception. Decimal strings at the API stop a JavaScript client's float rounding from reaching the ledger.
