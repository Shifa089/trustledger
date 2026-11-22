# Changes

## DAY17 (M9, FR-AML-1/2): AML rules R1-R4, RuleEngine and cases

**Goal:** continue M9 in `risk-aml-service` (SPEC FR-AML-1, FR-AML-2, §2 "AML rules", §4 `aml` tables):
1. an `AmlRule` interface (`code`, `severity`, `evaluate(walletId, newFact) -> Optional<AlertDetails>`) and rules
   R1-R4 using windowed SQL over `transaction_facts`, thresholds in an `AmlProperties` record;
2. a `RuleEngine` that runs all rules after each fact, creates alerts and attaches them to the wallet's single
   active case (opening one if there is none);
3. the injected `Clock` for every window.

Branch: `feature/day17-aml-rules` (created from `master`), merged into `master`.

Decisions approved in the plan:
- **Only money the owner moves counts.** R1 and R4 count outgoing transfers; R2 and R3 count outgoing transfers
  and top-ups. An incoming transfer never alerts the receiver: otherwise anyone could get a victim's wallet frozen
  by sending it three ₹46,000 transfers. It also matches the M9 "done when" (the **sender's** wallet is frozen).
- **Windows end at the injected `Clock`'s "now"**: `occurred_at >= now - window`, start included, no upper bound
  (a fact stamped slightly ahead by core-app's clock still counts). Trade-off: a fact that arrives after its window
  has passed (large consumer lag) is not counted against it.
- **R2 range ₹45,000.00-₹49,999.99, both ends included** (just under the ₹50,000 high-value hold).
- **No repeat alerts within a rule's window.** The 7th, 8th... transfer in ten minutes doesn't add an R1 alert
  each (and, next session, a freeze request each). R3 has no window: every large transaction is its own alert.
- **Same transaction as the facts.** The engine runs inside `TransactionFactRecorder`'s transaction, after
  `processed_events` and the facts: a duplicate event is never evaluated, and a failure rolls everything back for
  the Kafka retry.

One change from the plan: the plan said "open a case with `INSERT ... ON CONFLICT DO NOTHING`". The per-wallet
lock (below) already serialises everything that creates a case, so a plain "find the active case, or save a new
one" is safe and stays in JPA. The partial unique index from V3 is still the backstop. No new migration, no new
dependency.

---

### 1. Rules (`com.trustledger.aml.rules.domain`)

- **`AmlRule`** (Strategy): `code()`, `severity()`, `evaluate(walletId, newFact)`, and `window()` (default empty)
  for repeat suppression.
- **`Severity`** (MEDIUM, HIGH), **`AlertDetails(description)`**: counts, amounts, windows and IDs only, never
  personal data, e.g. `6 outgoing transfers in PT10M (more than 5); latest transfer <id>`. Amounts are formatted
  from paise with integer arithmetic.
- **`FactWindowQueries`** (port): `countOutgoingTransfersSince`, `countInitiatedInAmountRangeSince`,
  `countDistinctRecipientsSince`.
- **`WalletEvaluationLock`** (port): serialises evaluation per wallet until the transaction ends.
- The four rules, each a `@Component` with the port, `Clock` and its own thresholds:

| Rule | Checked only when the new fact is... | Query | Fires when |
|---|---|---|---|
| `VelocityRule` R1, MEDIUM | an outgoing transfer | outgoing transfers since now - 10m | count > 5 |
| `StructuringRule` R2, HIGH | owner-initiated and inside the range | owner-initiated in range since now - 24h | count >= 3 |
| `LargeAmountRule` R3, MEDIUM | owner-initiated | none: the fact alone decides | amount >= ₹1,00,000 |
| `FanOutRule` R4, HIGH | an outgoing transfer | distinct recipients since now - 24h | count >= 10 |

  R2 skips a fact outside the range, so an unrelated transaction doesn't re-trigger it.
- **`TransactionFact`** gets `isOutgoingTransfer()` and `isInitiatedByWalletOwner()`, so the "what counts" rule
  lives in one place.

### 2. Thresholds (`AmlProperties`, `trustledger.aml.rules.*`)

A record with one nested record per rule; amounts in paise like the facts. Startup fails on: a missing rule,
counts or amounts below 1, an upside-down R2 range, a window that is zero, negative or longer than 31 days.
Severities stay in code (the spec doesn't make them configurable).

```yaml
trustledger.aml.rules:
  velocity:     { max-outgoing-transfers: 5, window: 10m }
  structuring:  { min-amount-minor: 4500000, max-amount-minor: 4999999, min-transactions: 3, window: 24h }
  large-amount: { min-amount-minor: 10000000 }
  fan-out:      { min-distinct-recipients: 10, window: 24h }
```

Bound by `RulesConfig` (`@EnableConfigurationProperties`).

### 3. Windowed SQL and the lock (`com.trustledger.aml.rules.infrastructure`)

- **`JdbcFactWindowQueries`**: `count(*)` / `count(DISTINCT counterparty_wallet_id)` over `aml.transaction_facts`,
  each starting with `wallet_id = ? AND occurred_at >= ?`, i.e. one range scan on the existing
  `ix_transaction_facts_wallet_occurred` index. The window start is sent as `OffsetDateTime` in UTC (pgjdbc's
  native type for `timestamptz`), so the JVM time zone can't shift the edge.
- **`PostgresWalletEvaluationLock`**: `pg_advisory_xact_lock(key)` with the wallet UUID folded into 64 bits. There
  is no wallet row in this service to `SELECT ... FOR UPDATE`; an advisory lock needs no table and is released at
  commit or rollback. Two wallets sharing a key only wait for each other.
- **Flush before counting.** The rules' SQL is plain JDBC, which doesn't trigger Hibernate's auto-flush, so
  `TransactionFactRepository` gets `flush()` (resolved by Spring Data to `SimpleJpaRepository.flush()`), and the
  recorder flushes before evaluating.

### 4. Cases and alerts (`com.trustledger.aml.cases`)

- **`AmlCase`** entity (`aml.cases`): `open(walletId, now)`, `alertRaised(now)` (moves `updated_at`; refused on a
  finished case), `@Version`, no setters. The workflow (assign, close, escalate) comes with the case endpoints.
- **`Alert`** entity (`aml.alerts`): `raise(case, ruleCode, severity, details, now)`; only on an active,
  already-saved case; the wallet is the case's wallet; every column `updatable = false`.
- **`CaseStatus`** with `ACTIVE = {OPEN, INVESTIGATING}`.
- Ports `AmlCaseRepository` (`findActiveByWalletId`, JPQL) and `AlertRepository`
  (`existsByWalletIdAndRuleCodeAndCreatedAtGreaterThanEqual` for suppression), implemented by Spring Data
  interfaces on the bare `Repository` (no delete).

### 5. RuleEngine (`com.trustledger.aml.rules.application`)

`RuleEngine.evaluate(newFacts)`, `@Transactional(propagation = MANDATORY)` so it can only run inside the
recorder's transaction:
1. sort the new facts by wallet ID (a transfer has the sender's OUT fact and the receiver's IN fact);
2. per wallet, take the lock (ascending order, so two transactions can't deadlock), then run every rule;
3. per hit: skip it if the same rule alerted for the wallet within its window; otherwise find the active case or
   open one, save the alert and touch the case;
4. return `RaisedAlert(alertId, caseId, walletId, ruleCode, severity)`: the hook for FR-AML-3 next session.

Why the lock matters: facts are inserted before the lock is taken. When two transactions record a fact for the
same wallet at once, the second waits; when it gets the lock, the first has committed, and under READ COMMITTED
its next query sees the first one's fact. The last transaction therefore always sees all of them.

**`TransactionFactRecorder`** now saves the facts, flushes and calls the engine, all in its one transaction.
Logs: rule, severity, wallet and case IDs only.

### 6. Tests

**Unit (66, were 27):**
- `VelocityRuleTest` (4), `StructuringRuleTest` (7), `LargeAmountRuleTest` (5), `FanOutRuleTest` (4), with a
  mocked port and a fixed clock. **One boundary pair per threshold:** R1 5 no / 6 yes; R2 count 2 no / 3 yes and
  amounts ₹44,999.99 and ₹50,000.00 ignored, ₹45,000.00 and ₹49,999.99 checked; R3 ₹99,999.99 no / ₹1,00,000.00
  yes; R4 9 no / 10 yes. Each rule queries from `now - window`; facts that don't apply never reach the port; an
  incoming transfer never alerts the receiver.
- `AmlPropertiesTest` (5): required rules, positive values, R2 range order, window limits.
- `AmlCaseTest` (6): a new case is OPEN; `alertRaised`; active statuses; a finished case takes no alerts; an
  alert belongs to its case and wallet; an alert needs a saved case.
- `RuleEngineTest` (8): no hit touches nothing; the first hit opens a case; a later hit joins it; two rules share
  one case; a repeat within the window is suppressed; a rule without a window alerts every time; wallets locked and
  evaluated in ascending ID order; each rule gets the fact's own wallet.
- `TransactionFactRecorderTest` (3, updated): flush, then evaluate both facts; a duplicate is never evaluated.

**Integration (35, were 21): `RuleEngineIT` (14)**, real PostgreSQL and Kafka, a `MutableClock` as `@Primary`
`Clock`, fresh wallet IDs per test:
- **Window edges, to the millisecond:** a fact exactly at `now - 10m` counts for R1 and one at `now - 10m - 1ms`
  doesn't; the same at `now - 24h` for R2 and R4.
- R1 alerts once per window, not once per transfer; a new burst after the window alerts again on the same case.
- R2 counts only owner-initiated facts inside the range (two incoming ₹46,000 and ₹44,999.99 / ₹50,000.00
  transfers ignored; top-ups count); fires on the third.
- **Three ₹46,000 transfers sent over Kafka:** exactly one R2 HIGH alert for the sender, an OPEN case, nothing for
  the receiver.
- R3 at ₹1,00,000 and not at ₹99,999.99. R4 counts each recipient once.
- **Cases:** alerts join the active case while OPEN or INVESTIGATING; after it is closed, the next alert opens a
  new case.
- **A duplicate event raises no second alert.**
- **Concurrency:** 6 transfers of ₹1,00,000 from one wallet recorded at the same moment from 6 threads: exactly one
  R1 alert, six R3 alerts, one active case. Checked by mutation: with the lock turned into a no-op the test failed
  3 runs out of 3 (two transactions both inserted a case and hit the unique index).

### 7. Docs

- `docs/SPEC.md` §2, under the AML rules table: what each rule counts, the R2 range, how windows are anchored, and
  repeat suppression.
- This file.

### 8. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS** (6 min 1 s).
- event-contracts: 31 unit tests.
- core-app: 1082 unit tests and 613 integration tests (unchanged).
- risk-aml-service: 66 unit tests and 35 integration tests (were 27 and 21).

0 failures, 0 errors.

### Still open (next M9 session)

- HIGH alert → `AccountFreezeRequestedV1` (from `RaisedAlert`); core-app listener freezes the wallet with source
  AML; end-to-end: 3 transfers of ₹46,000 freeze the sender's wallet.
- Case workflow endpoints (OPEN → INVESTIGATING → CLOSED_FALSE_POSITIVE / ESCALATED) and STR CSV export.
- `docs/adr/0007-separate-aml-service.md` already exists; extend it if the freeze flow needs a decision record.
- Metric `trustledger_aml_alerts_total{rule}` belongs to M12.

### What to learn from this

1. **Decide who can trigger a rule, not just what it counts.** "3 transactions of ₹46,000" sounds direction-free,
   but counting money a wallet *receives* lets an attacker freeze anyone's wallet. Security thinking belongs in
   business rules too.
2. **Boundaries are where rules break.** "More than 5" and "5 or more" differ by one transfer; "within 24 hours"
   needs a decision about the start instant. Each threshold gets a pair of tests, at the limit and one step below,
   and the window edges are tested against the real SQL to the millisecond.
3. **Check-then-act needs a lock or a constraint.** Counting facts and "find the case or create it" are both
   check-then-act. Two transactions can both see "5" or both see "no case". A per-key advisory lock serialises
   them; the unique index makes the rare escape fail loudly instead of silently. Taking several locks in ascending
   order rules out deadlocks.
4. **Prove the concurrency test can fail.** A concurrency test that passes might just be lucky timing. Removing
   the lock and watching the test go red (3 of 3 runs) shows it really guards the lock.
5. **Mixing JPA and JDBC needs a flush.** Hibernate keeps inserts in memory until it has to write them; plain JDBC
   reads what's in the database. Flush before the JDBC query, or the rules miss the fact that triggered them.
6. **Inject time.** Every window starts from `Clock`, so tests set "now" exactly and move it forward, instead of
   sleeping or hoping a test finishes inside a 10-minute window.
