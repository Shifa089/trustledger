# ADR 0004: Pessimistic row locks in ascending ID order for money movement

- Status: Accepted
- Date: 2026-11-19
- Spec: docs/SPEC.md §4 "Transfer flow" (step 3), FR-P-2, FR-P-3; §8 M6 "Done when" 2, 3, 5; §9 must-pass 4, 5, 7

## Context

A transfer reads two wallet balances, decides (frozen? enough available? under the limits? under the
receiver's cap?), and then writes both. If two transactions run that read-decide-write at the same time on the same
wallet, both can decide on the same old balance:

- **Lost update / overdraft.** Wallet A has ₹100. Two ₹100 transfers both read ₹100, both pass the
  balance check, and both debit. The database `CHECK (balance_minor >= 0)` would stop the second write at commit,
  but only for the balance. The daily and monthly limits are *sums over other rows* (`payments.transfers`), so
  no single-row constraint can protect them.
- **Deadlock.** A→B and B→A at the same time. If each transaction locks "its own" sender first, T1 holds A and
  waits for B while T2 holds B and waits for A. PostgreSQL detects the cycle after `deadlock_timeout` (1 s) and
  aborts one of them with `40P01`. That is a failed customer request plus a one-second stall on both wallets,
  every time it happens.
- **Compliance decisions** (FR-P-3): two officers may approve, or one approve and one reject, the same held
  transfer at the same moment. Exactly one decision may win, and the hold may be released exactly once.

The checks must run on data that can't change before commit, and the design must make the deadlock cycle
impossible, not just rare.

## Options

1. **Optimistic locking only** (`@Version` on `Wallet`, retry on conflict). There is no waiting, but every
   collision becomes a rollback plus a retry. Under the 100-concurrent-transfers load, most attempts fail and retry
   repeatedly. The limit sums still aren't protected: two transfers can each read the same sum, and they only
   conflict if they also write the same wallet row. They do, so it works, but only by accident of the data model.
   Retrying a money operation also needs care, because the idempotency key is already claimed.
2. **`SERIALIZABLE` isolation.** PostgreSQL's SSI detects every anomaly, including the limit sums. The cost is
   serialization failures (`40001`) under contention that the application must retry, with the same retry
   problem as option 1. Every transaction pays the cost, including read-only ones, unless the isolation level is
   set per transaction.
3. **Pessimistic row locks** (`SELECT ... FOR UPDATE`) on every wallet the transaction will touch, taken
   **before** any check, **in one global order**. Contenders wait instead of failing. The checks read the
   locked rows, so they hold until commit. The limit sums are protected because every transfer from a wallet
   first locks that wallet.
4. **Single writer per wallet** (a queue or actor per wallet ID). There is no lock contention in the database,
   but it needs infrastructure we don't have (partitioned consumers), turns a synchronous `201` into an async
   flow, and still has to coordinate across two wallets.

## Decision

Option 3, with these rules:

- **Lock before deciding.** `WalletApi.lockForUpdate(ids)` runs
  `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE` (JPA `PESSIMISTIC_WRITE`) and returns snapshots read
  under the locks. Every check (status, available balance, limits, receiver cap) uses those snapshots, never an
  earlier unlocked read.
- **One global order: ascending wallet ID, as PostgreSQL orders it.** The order is `ORDER BY id` in SQL, not a
  sort in Java. `java.util.UUID.compareTo` compares two signed `long`s and disagrees with PostgreSQL's byte-wise
  `uuid` order for about half of all pairs. Because every transaction asks for the smaller ID first, whoever gets
  the first lock can always get the second one. The other transaction waits on the first lock while holding
  nothing, so no cycle can form.
- **Compliance decisions lock the transfer row first, then the wallets** (`TransferRepository.findByIdForUpdate`,
  then `lockForUpdate`). Two decisions on one transfer queue on the transfer row, and the second one sees
  `COMPLETED`/`REJECTED` and gets `409 INVALID_STATE_TRANSITION` before it touches a wallet.
  `TransferService.transfer()` never locks an *existing* transfer row (it only inserts its own), so the order
  "transfer → wallets" can't form a cycle with "wallets only".
- **No remote calls while locks are held** (CLAUDE.md). A lock held across an HTTP call to Razorpay would make every
  transfer on that wallet wait on the network.
- **Keep `@Version` as a safety net.** Wallets and transfers keep their version column. A pessimistic lock
  protects the *row*; `@Version` catches the cases where the Java object is stale (see Consequences).
- **Database constraints stay** (`balance_minor >= 0`, `held_minor <= balance_minor`, the ledger's balanced-entry
  trigger and unique business reference) as a last line of defence that holds even if application code is wrong.

## Consequences

- **Correct under contention, proven by tests:**
  - `TransferConcurrencyIT`: 100 concurrent ₹100 transfers from a ₹5,000 wallet give exactly 50 successes, a final
    balance of 0, and a ledger that equals the cache.
  - `DeadlockFreedomIT`: A→B and B→A 200 times give zero `40P01` and preserved totals. Its control test locks two
    rows in opposite orders on purpose and *does* get exactly one `40P01`, which proves the detection works.
  - `HeldTransferServiceIT`: 20 concurrent approve/reject calls on one held transfer give exactly one decision and
    one release of the hold.
- **A hot wallet serialises its transfers.** All transfers from one wallet run one at a time. That fits a
  personal wallet: a customer does not send hundreds of transfers per second. A merchant wallet receiving many
  payments would contend on the receiver lock. If that becomes real, split the hot wallet or credit it
  asynchronously, and record that in a new ADR.
- **Lock waits are unbounded by default** (`lock_timeout = 0`). With ordered locks a wait always ends when the
  holder commits, and holders make no remote calls, so waits are short. M13 should set a `lock_timeout`
  (e.g. 5 s) so that a bug can't stall requests indefinitely.
- **The persistence context is a cache that ignores locks.** Two pitfalls were found while building this, and both
  are documented in code:
  - Day 10: a wallet read *unlocked* earlier in the transaction stayed in the persistence context, and the
    `FOR UPDATE` query would have returned that stale object. `lockForUpdate` now evicts unlocked copies first,
    and `@Version` turns any remaining case into an exception instead of a lost update.
  - Day 11: after Hibernate auto-flushes a changed wallet (for example because a ledger query runs), it reports
    the wallet's lock mode as `WRITE` instead of `PESSIMISTIC_WRITE`. `WalletApi` then refuses the next change as
    "not locked", although the row lock is still held. Callers therefore post to the ledger **before** changing
    any wallet, in both `transfer()` and `approve()`.
- **Tests must create real contention.** Each concurrency test submits all tasks to an `ExecutorService`, has
  them wait on one `CountDownLatch`, then releases them together, with a 60 s overall timeout so a hang fails
  the build instead of blocking it.
