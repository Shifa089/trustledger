# Changes

## DAY7: Milestone M5 part 1: ledger module (scaffolding + failing tests for `post()`)

**Goal:** everything around `LedgerPostingService.post()`, so it can be written by hand on Day 8. The parts:
- **Tables.** `ledger.ledger_accounts`, `ledger.journal_entries` and `ledger.journal_lines`. Seeded accounts: `PLATFORM:GATEWAY_CLEARING` and `PLATFORM:SUSPENSE`.
- **Safety nets.** Append-only triggers (no UPDATE, DELETE or TRUNCATE), and a **deferred constraint trigger** that checks each entry at commit: at least 2 lines, debits = credits, one currency.
- **Code.** Entities, repositories, `PostingCommand` and `PostingLine` records. `LedgerApi` has `post(...)`, `reverse(entryId, reason)`, `balanceOf(accountCode)` and `openWalletAccount(...)`.
- **Wallet accounts.** On `WalletCreated`, a listener **in the wallet module** opens `WALLET:<walletId>` (LIABILITY, credit normal side) and stores its ID on the wallet.
- **`LedgerPostingService.post()`** throws `UnsupportedOperationException`. **`LedgerPostingServiceIT` fails on purpose** until it is written.

Branch: `feature/m5-ledger` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **The listener lives in the wallet module, not the ledger.** CLAUDE.md says "ledger depends on no other business module". A ledger listener on `WalletCreated` that calls `WalletApi` would break that. Instead `wallet.application.WalletCreatedListener` calls `LedgerApi.openWalletAccount` and stores the ID with `Wallet.linkLedgerAccount`. The dependency now runs wallet → ledger, and `ModularityTests` stays green.
2. **New `reason` column on `journal_entries`.** `reverse(entryId, reason)` needs somewhere to keep the reason. It is required for a REVERSAL and forbidden otherwise. SPEC §4 is updated.
3. **New error code `DUPLICATE_POSTING` (409).** The other refusals reuse existing codes: `VALIDATION_FAILED` for shape, balance, currency and zero amounts, `NOT_FOUND` for an unknown account or entry, and `INVALID_STATE_TRANSITION` for reversing a reversal.
4. **Reversal is implemented today, through `post()`.** `LedgerReversalService` builds the flipped lines and calls `post()`, so a reversal passes the same checks as any posting. Its tests that actually post are therefore red until `post()` exists.
5. **Reconciliation, ShedLock and the mismatch metric are M5 part 2.** ShedLock is not in the CLAUDE.md stack, so it needs an ADR first.

---

### 1. How to verify Day 7

Run these from `dev/`. Start **Docker Desktop** first. Every cell holds exactly **one** command; paste them one at a time.

> **PowerShell:** put every `-D...` argument that contains a dot in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build. **Expected to end in BUILD FAILURE today**, with only the `post()` tests red (listed below). Everything else is green. |
| 2 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=LedgerMigrationIT,LedgerAccountServiceIT,WalletCreationScenarioIT"` | The ledger ITs that must pass **today**: the database safety nets, balances and wallet-account creation. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=LedgerPostingServiceIT,LedgerReversalIT"` | The red tests to turn green on Day 8 by writing `post()`. |
| 4 | `./mvnw -pl core-app test "-Dtest=ModularityTests"` | The ledger depends on no business module, and there are no cycles. |
| 5a | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak localstack` | Starts the local infrastructure. |
| 5b | `./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"` (in a **second** terminal) | Flyway applies **V7** and **V8**. Wait for `Started TrustLedgerApplication`. |
| 6 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "select code, type, normal_side from ledger.ledger_accounts"` | The two seeded platform accounts, plus a `WALLET:<id>` row for every wallet created **after** V7. |
| 7 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "update ledger.journal_lines set amount_minor = 1"` | Fails with `ledger.journal_lines is append-only: UPDATE is not allowed`, even as the table owner. |

Wallets created on Day 6 (before V7) have no ledger account yet (`ledger_account_id` is null). See "Deferred".

**Results:** `./mvnw clean verify -fae` ended in **BUILD FAILURE, as planned: only the `post()` tests are red.**
- **Unit tests:** core-app **606/606 green**, up from 543 (+63). `ModularityTests` is green: the ledger depends on no business module, and there are no cycles.
- **Integration tests:** core-app **365 run, 350 green, 15 red**, up from 318 (+47). The 15 red tests all fail with `UnsupportedOperationException: LedgerPostingService.post() is not implemented yet`:
  - `LedgerPostingServiceIT`: 13 of 14 (all except `negativeAmountCannotBeExpressed`)
  - `LedgerReversalIT`: 2 of 6 (`reversalFlipsEveryLineAndNetsTheBalanceToZero`, `secondReversalIsADuplicate`)
- **AML service:** the IT passed (1/1).
- **JaCoCo:** no report this time, because the failsafe check stops the build before the `report` goal. The numbers come back on Day 8, when everything is green.
- **Note:** a red `master` until Day 8 is intentional (CLAUDE.md: "I will write X myself" → failing tests and scaffolding only). Nothing is pushed, so CI is not affected.

---

### 2. What changed and why

#### Database

| Object | Change | Why |
|---|---|---|
| `V7__ledger_create_ledger_accounts.sql` | `ledger.ledger_accounts`: `id`, `code`, `type`, `normal_side`, `currency`, `created_at`. **No balance column.** | SPEC §4. A balance is always summed from the lines, so it can't drift from them. |
| `uk_ledger_accounts_code`, `ck_ledger_accounts_code` | Unique code in the form `KIND:id`, e.g. `WALLET:<uuid>` | One account per wallet. A typo like `wallet:x` is refused. |
| `ck_ledger_accounts_side_matches_type` | ASSET iff DEBIT normal side | Accounting identity: an asset that grows with credits would give every balance the wrong sign. |
| Seed rows | `PLATFORM:GATEWAY_CLEARING` (ASSET, DEBIT) and `PLATFORM:SUSPENSE` (LIABILITY, CREDIT), with fixed UUIDs | The top-up posting rule debits clearing (M7). Suspense holds money that can't be attributed yet, which is owed to someone, so it is a liability. |
| `V8__ledger_create_journal.sql` | `ledger.journal_entries` (with a new `reason` column) and `ledger.journal_lines` | FR-L-1. |
| `uk_journal_entries_reference` | Unique (`reference_type`, `reference_id`, `entry_type`) | The same business event can't be posted twice, even by two racing transactions. |
| `uk_journal_entries_reverses`, `fk_journal_entries_reverses`, `ck_journal_entries_reversal`, `ck_journal_entries_reason`, `ck_journal_entries_not_self` | Reversed at most once; `reverses_entry_id` and `reason` iff REVERSAL; never reverses itself | FR-L-3 in the database too. |
| `ck_journal_lines_amount_positive`, `uk_journal_lines_entry_line`, FKs to the entry and the account | Amount > 0; line numbers unique per entry | The direction carries the sign. FKs are allowed because they stay inside the schema. |
| `ix_journal_lines_account_entry` | Index on (`account_id`, `entry_id`) | `balanceOf` and the M5 part 2 reconciliation read lines by account. |
| `ledger.reject_journal_mutation()` + 4 triggers | BEFORE UPDATE/DELETE (per row) and BEFORE TRUNCATE (per statement) on both journal tables raise `restrict_violation` | FR-L-2: append-only even for raw SQL. Same pattern as `identity.consents`. |
| `ledger.check_entry_balanced()` + 2 **constraint triggers** | `AFTER INSERT ... DEFERRABLE INITIALLY DEFERRED` on entries **and** lines. At COMMIT: ≥ 2 lines, debits = credits, exactly one currency. Raises `check_violation`. | The database's own proof of FR-L-1. Deferred because lines arrive one INSERT at a time. Firing on entries too catches an entry with no lines at all. |

#### Ledger module (new)

| File | Change | Why |
|---|---|---|
| Base package: `LedgerApi`, `PostingCommand`, `PostingLine`, `AccountBalance`, `AccountCodes`, `EntryType`, `Direction`, `AccountType`, `package-info` | The public API | Modulith: other modules see only these. `AccountCodes.wallet(id)` and the platform constants mean nobody hand-writes `"WALLET:" + id`. |
| `PostingCommand` | `(entryType, referenceType, referenceId, lines, reversesEntryId, reason)`. Factories `of(...)` and `reversal(originalId, reason, lines)`. Lines are copied with `List.copyOf`. Checks only its own shape: reference type `[A-Z_]{1,32}`, reversal fields iff REVERSAL. | The ledger rules (2 lines, balance, currency, positive amounts) belong to `post()`, which is yours to write. |
| `AccountBalance` | Signed `balanceMinor` on the normal side. `toMoney()` throws if it is negative. | Platform accounts can go negative. A wallet balance can't, and comparing it as `Money` will catch that. |
| `domain/LedgerAccount` | `@Immutable`. `open(code, type, currency, now)` derives the normal side from the type. `balanceFrom(debits, credits)` uses `subtractExact`. | An account never changes, and the arithmetic never wraps around. |
| `domain/JournalEntry`, `domain/JournalLine` | `@Immutable`. `JournalEntry.start(...)` and `addLine(account, direction, amountMinor)` number lines 1..n. Adding to a saved entry throws. The amount must be > 0. The `reason` is not in `toString` or the logs. | FR-L-2 in Java too. The entry deliberately does **not** check the balance; that is `post()`'s job. |
| `domain/*Repository` + `infrastructure/Jpa*Repository` | `findAllByCodeIn` (one query for every account in a posting), `existsByReferenceTypeAndReferenceIdAndEntryType`, `existsByReversesEntryId`, `saveAndFlush`, and `sumAmount(accountId, direction)` (JPQL `coalesce(sum, 0L)`) | The ports `post()` needs, ready to use. |
| `application/LedgerPostingService` | `@Transactional UUID post(PostingCommand)` throws `UnsupportedOperationException`. The class Javadoc lists the 6 steps. | Written by hand on Day 8. |
| `application/LedgerReversalService` | Reason 1-500 characters (`VALIDATION_FAILED`). Unknown entry → `NOT_FOUND`. A reversal → `INVALID_STATE_TRANSITION`. Already reversed → `DUPLICATE_POSTING`. Otherwise it flips every line and calls `post(PostingCommand.reversal(...))`. | FR-L-3 and the SPEC §4 posting rule "reversal of E". The unique keys stop two racing reversals. |
| `application/LedgerAccountService` | `openWalletAccount` (idempotent, LIABILITY, joins the caller's transaction) and `balanceOf` (sums lines; `NOT_FOUND` for an unknown code) | The account and the wallet link commit together. |
| `application/LedgerApiImpl` | Delegates. `post()` refuses a REVERSAL (use `reverse()`). | Reversal lines must come from the original entry, never be hand-made by a caller. |

#### Wallet, shared and docs

| File | Change | Why |
|---|---|---|
| `wallet/domain/Wallet.linkLedgerAccount(id, now)` | Set once. The same ID again does nothing. A different ID throws `IllegalStateException`. | Two ledger accounts for one wallet would be a serious bug, so it fails loudly. |
| `wallet/application/WalletLedgerLinkService` | Locks the wallet row, returns at once if already linked, otherwise calls `LedgerApi.openWalletAccount`, links and saves. | Idempotent, and the row lock serializes two attempts for the same wallet. |
| `wallet/application/WalletCreatedListener` | `@ApplicationModuleListener` on `WalletCreated` → `link(walletId)` | Runs after the wallet commit, in its own transaction (same pattern as `KycLevelUpgradedListener`). |
| `shared/ErrorCode.DUPLICATE_POSTING` | 409 | See decision 3. |
| `docs/SPEC.md` §4, §5 | The `reason` column, the seeds, who opens wallet accounts, what the deferred trigger checks, the new error code | Docs match the code. |

#### Tests

| File | Status today | What it proves |
|---|---|---|
| `LedgerPostingServiceIT` (14) | **13 red, 1 green** | A balanced entry is saved with its lines (types, reference, directions, amounts, `line_no` 1..n) and `balanceOf` reflects it. A transfer moves the balance between wallets. A 3-line entry works. Rejected, with **nothing written**: unbalanced, only debits, a single line, no lines, mixed line currencies, a line currency ≠ the account currency, a zero amount, an unknown account (`NOT_FOUND`), a duplicate reference (`DUPLICATE_POSTING`, one row, balance counted once). **Concurrency: 8 threads post the same reference at once → exactly 1 entry ID and 7 × `DUPLICATE_POSTING`.** The green one: a negative amount can't even be expressed (`Money` refuses it). |
| `LedgerReversalIT` (6) | **2 red, 4 green** | Red: a reversal flips every line, points to the original, keeps the reason and nets the balance to 0; a second reversal → `DUPLICATE_POSTING`. Green (refused before `post()`): reversing a reversal, an unknown entry, a blank reason, a hand-made REVERSAL sent to `post()`. |
| `LedgerMigrationIT` (19) | green | Seeds exist. **Unbalanced, single-line, line-less and mixed-currency entries are refused at COMMIT** and leave no row. An entry may be unbalanced mid-transaction if it is balanced at commit. UPDATE, DELETE and TRUNCATE are refused on both tables and the data is unchanged. Amount 0 or negative, a duplicate reference, a second reversal, broken reversal or reason fields, a bad type or reference type, an unknown account, a wrong normal side, and a bad or duplicate code are all refused. |
| `LedgerAccountServiceIT` (4) | green | A wallet account is a credit-normal INR liability, opened once. A new account has balance 0. Balances derived from raw-SQL postings on each normal side. An unknown code → `NOT_FOUND`. |
| `WalletCreationScenarioIT` (+2 → 7) | green | **Real minimum KYC → wallet → `WALLET:<id>` account linked**, LIABILITY/CREDIT/INR, balance 0. Linking again → same ID, one account row. |
| Unit: `PostingCommandTest` 11, `LedgerTypesTest` 7, `JournalEntryTest` 10, `LedgerAccountTest` 6, `LedgerReversalServiceTest` 9, `LedgerAccountServiceTest` 6, `LedgerApiImplTest` 4, `WalletLedgerLinkServiceTest` 3, `WalletCreatedListenerTest` 1, `WalletTest` +4, `ErrorCodeTest` +1 | green | Record shapes, line numbering, immutability after save, normal-side arithmetic and overflow, reversal flipping and every refusal, idempotent account opening, set-once wallet link. |

**Fixes found while testing**

- **`P2P_TRANSFER` is not a valid reference type.** My raw-SQL test helper reused the entry type as the reference type. The digit in `P2P` broke `ck_journal_entries_reference_type`, so `sameReferenceUnderAnotherEntryTypeIsAllowed` failed. Worse, the test wasn't testing its own name, because the two rows had different reference types. **Fix:** the helper uses a fixed `PAYMENT` reference type (or `JOURNAL_ENTRY` for a reversal). Payments will use reference types like `TOPUP` and `TRANSFER`.
- **`mvn clean` couldn't delete `target/surefire-reports`.** My own shell was sitting in that folder (Windows locks a process's current directory). Re-running from `dev/` fixed it. Not a code problem.

---

### 3. Machine setup done on Day 7 (not in Git)

- **Branch** `feature/m5-ledger` created from `master`, committed and merged back into `master` (`--no-ff`). Not pushed.
- No new dependencies, no `.env` changes, and no new Docker images.

### 4. Deferred

- **`LedgerPostingService.post()`**: Day 8, by hand. See section 6.
- **Reconciliation job + ShedLock + mismatch metric** (FR-L-4): M5 part 2. It compares wallet cached balances with `LedgerApi.balanceOf`. It must live **outside** the ledger (in wallet or reporting), so the ledger keeps depending on nothing. ShedLock needs an ADR.
- **Backfill** of ledger accounts for wallets created before V7: `WalletLedgerLinkService.link` is idempotent, so a one-off job (or the reconciliation job) can call it for every wallet whose `ledger_account_id` is null.
- **Retry of a failed `WalletCreatedListener`**: M8 (event publication registry). Until then a failure leaves the wallet unlinked; it is safe to retry.
- **`GET /admin/ledger/accounts/{code}/balance`** (ADMIN, AUDITOR): with reconciliation in part 2.
- **Posting from payments** (top-up and transfer) and the wallet-balance update in the same transaction: M6/M7.

---

### 5. What to learn from Day 7

1. **Double entry is a closed system.** Every movement debits one place and credits another by the same amount, so the sum over all accounts never changes. If any entry is unbalanced, money was created or destroyed, and reconciliation can't tell you where. That is why balance is checked twice: in `post()` and by the database.
2. **Deferred constraint triggers check the transaction, not the statement.** An entry and its lines are several INSERTs, and halfway through, the entry is unbalanced. A normal trigger would fire on the first line and refuse it. `DEFERRABLE INITIALLY DEFERRED` waits for COMMIT, when the entry is complete, and then refuses the whole transaction. `entryMayBeUnbalancedMidTransactionIfBalancedAtCommit` proves both halves.
3. **Append-only + reversal = an audit trail you can trust.** Because rows can never change, every balance at every point in time can be rebuilt from the journal. A mistake is fixed by a new entry that points to the old one, so the mistake **and** its correction both stay visible. (A superuser could still disable a trigger; that is what the hash-chained audit log in M10 and database permissions in M14 are for.)
4. **Normal sides make balances positive.** A wallet is a liability: the platform owes the customer, so money in is a *credit*. Reading a wallet "debits minus credits" would show every customer a negative balance. Storing `normal_side` on the account, and checking ASSET iff DEBIT in the database, keeps the sign in one place.
5. **"Check, then insert" needs a unique key behind it.** `post()` will first ask "already posted?" for a friendly error, but two racing transactions can both get "no". The unique `(reference_type, reference_id, entry_type)` lets exactly one insert win. The loser must turn the database error into `DUPLICATE_POSTING`, and the 8-thread test checks exactly that. (Same lesson as Day 6's wallet creation, now for money.)
6. **Dependency direction is a design decision.** "Ledger listens to wallet events" felt natural, but it would make the ledger, the most critical module, depend on a business module. Flipping it (wallet asks the ledger for an account) keeps the ledger reusable and testable on its own, and Modulith enforces it on every build.

---

### 6. What `LedgerPostingService.post()` must do (Day 8)

`@Transactional UUID post(PostingCommand command)`. It joins the caller's transaction, so in M6 the posting and the wallet balance updates commit or roll back together.

1. **Validate the shape (no database yet).** Throw `VALIDATION_FAILED` if:
   - there are fewer than 2 lines
   - any amount is zero (`Money` already refuses negatives)
   - the lines are not all in one currency
   - the debit total ≠ the credit total. Sum each side with `Math.addExact`, so a huge amount throws instead of wrapping around.
2. **Duplicate check.** If `entries.existsByReferenceTypeAndReferenceIdAndEntryType(type, id, entryType)` → `DUPLICATE_POSTING`. This is the cheap, friendly path for an ordinary retry.
3. **Resolve the accounts.** Call `accounts.findAllByCodeIn(distinct codes)` once and map them by code. Any code missing → `NOT_FOUND`. Any account whose currency ≠ the lines' currency → `VALIDATION_FAILED`.
4. **Build the aggregate.** `JournalEntry.start(entryType, referenceType, referenceId, reversesEntryId, reason, clock.instant())`, then `addLine(account, direction, amount.amountMinor())` for each line, **in the command's order** (the tests check `line_no`).
5. **Save and flush.** Call `entries.saveAndFlush(entry)`. If a concurrent transaction posted the same reference between steps 2 and 5, the unique key fires here: catch `DataIntegrityViolationException` and throw `DUPLICATE_POSTING`. This is what makes the 8-thread test pass.
6. **Return `entry.getId()`.** At COMMIT the deferred trigger checks the balance again as a backstop. If steps 1 and 3 are right, it never fires.

Keep in mind:
- No locks and no remote calls in `post()`; the caller holds the wallet locks.
- Use the injected `Clock`.
- Never log the reason.
- Validating everything before writing anything means a refused posting leaves no partial rows. `assertRejected` checks this for every rejection.
- After writing it, ask: *"Review my LedgerPostingService.post() for correctness, concurrency and clarity. Don't rewrite it; list issues."*
