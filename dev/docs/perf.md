# Performance notes

Measured numbers only. Each section says how the numbers were produced so they can be reproduced.

- §1: transaction history query plans (M11).
- §2: transfer API load test with k6, and virtual threads on vs off (M13).

## 1. Transaction history query (FR-S-1, M11, Day 22)

`GET /api/v1/wallets/me/transactions` reads the view `payments.wallet_transactions` (V25): a `UNION ALL` of three
branches (transfers sent, transfers received, top-ups), filtered by the caller's wallet, with JPA Specifications.

### How it was measured

- `TransactionHistoryQueryPlanIT` (run with `./mvnw verify`) seeds PostgreSQL 17 (Testcontainers) with **60,000 history
  rows**:
  - one "hot" wallet with **10,000** rows: 6,000 sent (every 50th held for review), 2,000 received, 2,000 top-ups;
  - 400 other wallets sharing 40,000 transfers and 10,000 top-ups.
- It then runs `ANALYZE` and `EXPLAIN (ANALYZE, BUFFERS)` for each query shape against the hot wallet.
- The SQL has the same predicates and `ORDER BY` as the SQL Hibernate generates from `WalletTransactionSpecs`.
- The full plans are written to `core-app/target/perf/history-explain.txt` on every run, and the test fails if a
  query plans a `Seq Scan` on `transfers` or `topups`.
- Laptop, warm cache (`shared hit` only). Absolute times are small and noisy; the plan shape and buffer counts are
  the signal.

### Indexes (V26)

| Index | Columns | Replaces |
| --- | --- | --- |
| `ix_transfers_sender_created_id` | `(sender_wallet_id, created_at, id)` | `ix_transfers_sender_created` (V11) |
| `ix_transfers_receiver_created_id` | `(receiver_wallet_id, created_at, id)` | `ix_transfers_receiver_created` (V22) |
| `ix_topups_wallet_created_id` | `(wallet_id, created_at, id)` | `ix_topups_wallet_created` (V13) |

Adding `id` as the last column matches the history's order, `created_at DESC, id DESC` (with `id` as tie-breaker).
Each branch can then return rows already sorted (`Index Scan Backward`). The old indexes are a prefix of the new ones,
so the daily and monthly limit sums and the data export still use them.

### Finding: a `WHERE` on a `UNION ALL` branch makes PostgreSQL sort

The first version of the view filtered two branches in the view itself:
`WHERE status IN ('COMPLETED','REVERSED')` on received transfers, and `WHERE status <> 'CREATED'` on top-ups.

Measured on the same data, the default first page:

```
Limit (actual time=3.052..3.061 rows=20)            Buffers: shared hit=148
  ->  Merge Append
        ->  Index Scan Backward using ix_transfers_sender_created_id on transfers t   (rows=1)
        ->  Sort  (top-N heapsort)                                                     <- all 2,000 received rows
              ->  Subquery Scan on "*SELECT* 2" -> Bitmap Heap Scan on transfers t_1
        ->  Sort  (top-N heapsort)                                                     <- all 2,000 top-ups
              ->  Subquery Scan on "*SELECT* 3" -> Bitmap Heap Scan on topups p
Execution Time: 3.094 ms
```

PostgreSQL flattens a `UNION ALL` view into one append (so the outer `ORDER BY ... LIMIT` reaches every branch) only
when no branch has its own `WHERE`. The two filtered branches stayed separate subqueries, so each one read and sorted
**all** of the wallet's rows in that branch. That cost grows with the history.

**Fix (V25 as committed):** the rule became a column, `visible`:
- `true` for sent transfers;
- `status IN ('COMPLETED','REVERSED')` for received transfers;
- `status <> 'CREATED'` for top-ups.

`WalletTransactionSpecs.matching` always adds `visible = true`, so the rule can't be forgotten, and no branch has a
`WHERE` any more.

### Results (after the fix)

| Query (hot wallet, 10,000 rows) | Plan | Buffers | Execution time |
| --- | --- | --- | --- |
| Page 0, newest first (default) | Merge Append of 3 × `Index Scan Backward`, stops after 20 rows, **no sort** | 12 | **0.066 ms** |
| Page 0, one month (`from`/`to`) | Merge Append of 3 × `Index Scan Backward`, `created_at` range in the **index condition** | 12 | 0.069 ms |
| Page 0, `type=TOPUP` | The other two branches are pruned; one `Index Scan Backward` on `ix_topups_wallet_created_id` | 4 | 0.041 ms |
| Count for `totalElements` (offset mode) | Append of 3 × Bitmap Index Scans, counts all 10,000 rows | 336 | 2.543 ms |
| Page 450 (`OFFSET 9000`), newest first | Reads all 10,000 rows and sorts them (quicksort, 1.7 MB) to skip 9,000 | 336 | 9.597 ms |
| **Keyset slice after row 9,000** (cursor mode) | Merge Append of 3 × `Index Scan Backward` **starting at the cursor**, 21 rows | 11 | **0.069 ms** |
| Page 0, `sort=amount,desc` | Bitmap scans of the wallet's rows + top-N heapsort (no index on amount) | 336 | 4.881 ms |

The default page before the fix: 148 buffers and 3.094 ms. After: 12 buffers and 0.066 ms.

The keyset slice (from `EXPLAIN`):

```
Limit (actual time=0.037..0.046 rows=21)                                         Buffers: shared hit=11
  ->  Merge Append   Sort Key: t.created_at DESC, t.id DESC
        ->  Index Scan Backward using ix_transfers_sender_created_id on transfers t
              Index Cond: ((sender_wallet_id = '0000beef-…'::uuid) AND (created_at <= '2026-01-17 22:24:00+00'))
              Filter: ((created_at < '2026-01-17 22:24:00+00') OR ((created_at = '2026-01-17 22:24:00+00') AND (id < 'af6b…'::uuid)))
        ->  Index Scan Backward using ix_transfers_receiver_created_id on transfers t_1   (same shape, + status filter)
        ->  Index Scan Backward using ix_topups_wallet_created_id on topups p             (same shape, + status filter)
Execution Time: 0.069 ms
```

### What the numbers say

- **Offset paging costs grow with the page number; keyset paging doesn't.** At row 9,000, `OFFSET` read 336 buffers
  and sorted 9,020 rows. The cursor read 11 buffers, about the same as page 0. Offset mode also pays a `COUNT(*)` over
  all the wallet's rows on every request. For long histories (statements, exports, infinite scroll) use
  `?cursor=`; offset mode stays for UIs that need page numbers and totals.
- **The redundant `created_at <= cursor` matters.** `WalletTransactionSpecs.olderThan` writes the keyset as
  `created_at <= c AND (created_at < c OR (created_at = c AND id < id_c))`. The first part becomes an `Index Cond`,
  so the scan starts at the cursor. Without it, the `OR` is only a `Filter`, and the scan would walk every newer row
  first.
- **Sorting by amount is a top-N sort of one wallet's rows** (4.9 ms for 10,000). It is whitelisted and bounded by the
  wallet, but it isn't indexed. If it becomes common, add `(wallet, amount_minor, created_at, id)` indexes; until
  then the extra write cost on every transfer isn't worth it.
- No query touches another wallet's rows: every plan's index condition starts with the caller's wallet ID.

## 2. Transfer API under load (M13, Day 24 A)

SPEC §2 target: **`POST /api/v1/transfers` p99 under 300 ms at 100 requests per second** on a laptop.

**Result: not met.** p99 stays under 100 ms at 10 req/s. At 50 req/s it is 397 ms. At 100 req/s the app reaches its
throughput ceiling: 56 to 80 completed transfers per second, with p99 of several seconds. No request failed, and no
money drifted.

### How it was measured

- **Script:** `load-tests/transfers.js` (k6 v2.4.0). How to run it, and how the data and tokens are set up:
  `load-tests/README.md`.
- **Data:** 200 seeded FULL-tier wallets (Spring profile `perf`, `PerfSeeder`), each starting with ₹1,00,000.
- **Requests:** a random sender and receiver, a random amount from ₹1.00 to ₹100.00, a new `Idempotency-Key` on every
  request, and one real Keycloak RS256 token per user. The tokens are fetched once in `setup()` and verified by the app
  on every request.
- **Load:** `constant-arrival-rate`. k6 starts requests at the given rate whether earlier ones have finished or not, at
  most 400 VUs. If the app is slower than the rate, the requests k6 could not start are counted as
  `dropped_iterations`.
- **App:** core-app with `local,perf` via `spring-boot:run`, on the same laptop as k6 and the Docker infrastructure
  (PostgreSQL 17, Redis 8, Kafka 4.4 and Keycloak 26.8 in Docker Desktop 29.9). risk-aml-service was not running.
  Defaults: virtual threads on, Hikari pool not configured (Hikari's default is 10 connections), log level INFO,
  tracing sampling 1.0 (`local` profile; no OTLP export without `obs`).
  - Per request, the money path runs: JWT validation, the Redis rate limiter (raised to 600 per minute by `perf`),
    the JIT user lookup, the idempotency claim, then the transfer transaction (two `SELECT ... FOR UPDATE`, limit
    sums, ledger posting, outbox row), then the idempotency response store.
- **Laptop:** Intel Core i5-13420H (8 cores, 12 threads), 15.7 GB RAM, Windows 11 Home, Java 25.0.4.1.
- **Raw k6 output** of every run below: `docs/perf-results/2026-11-23-*.txt`.
- **When:** 2026-11-23, one run per configuration. The runs weren't repeated, so the run-to-run variance is unknown.

### Results: ramp 10 → 50 → 100 req/s (2 minutes each, virtual threads on)

| Stage | Transfers completed | Throughput | Dropped | p50 | p95 | p99 | max | Errors | p99 < 300 ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10 req/s | 1,200 of 1,200 | 10 /s | 0 | 39.67 ms | 61.18 ms | **82.26 ms** | 288.78 ms | 0.00% | ✓ |
| 50 req/s | 6,001 of 6,000 | 50 /s | 0 | 67.25 ms | 250.78 ms | **396.66 ms** | 646.38 ms | 0.00% | ✗ |
| 100 req/s | 8,305 of 12,000 | ≈69 /s | 3,696 (whole run) | 5.63 s | 7.75 s | **9.07 s** | 12.28 s | 0.00% | ✗ |
| All | 15,506 | | 3,696 | 2.6 s | 7.26 s | 8.54 s | 12.28 s | 0.00% | ✗ |

- k6 reports throughput as count ÷ total test time (6.5 minutes). The "Throughput" column is count ÷ 120 s, the length
  of the stage. Rows 1 and 2 show that the app kept up there.
- k6 reports `dropped_iterations` for the whole run, 3,696. The "Insufficient VUs, reached 400" warning appeared only
  in the 100 req/s stage, and 12,000 − 8,305 = 3,695, so the drops belong to that stage.
- The count of 6,001 at 50 req/s is k6's own; it matches the rate within one request.
- `checks`: 100% of 31,012 passed (`status is 201`, `transfer COMPLETED`).

### Results: virtual threads on vs off at 100 req/s (2 minutes, `MODE=steady`)

Each run started on a freshly started app: no 10 and 50 req/s warm-up as in the ramp. Virtual threads were switched
off with `--spring.threads.virtual.enabled=false` (Tomcat's platform thread pool, 200 threads by default).

| Virtual threads | Transfers completed | Throughput | Dropped | p50 | p95 | p99 | max | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **on** (default) | 7,263 of 12,000 | 55.6 /s | 4,739 | 6.15 s | 11.41 s | **14.81 s** | 23.34 s | 0.00% |
| **off** | 10,210 of 12,000 | 79.9 /s | 1,791 | 4.51 s | 5.77 s | **6.49 s** | 8.15 s | 0.00% |

Neither configuration meets the target at 100 req/s. In this single pair of runs, platform threads completed 41% more
transfers (79.9 vs 55.6 per second), and their p99 was less than half as long (6.49 s vs 14.81 s).

### Correctness after the load

Checked in PostgreSQL after all runs. That is 33,348 transfers between the perf wallets: the three runs above plus a
369-transfer smoke run of the script.

| Check | Result |
| --- | --- |
| Sum of the 200 perf wallets' balances | ₹20,00,00,000.00 = 200 × ₹1,00,000 (money moved between wallets, none appeared or vanished) |
| Held amounts | 0 (no transfer reached the ₹50,000 hold) |
| Transfers by status | 33,348 COMPLETED, nothing else |
| Wallets whose cached balance ≠ ledger-derived balance | **0** |
| Journal entries whose debits ≠ credits | **0** |
| Lowest / highest perf wallet balance | ₹97,287.39 / ₹1,03,917.70 |

There were no deadlocks (they would be 500 errors; errors were 0%), and no request failed at any load. Above the
ceiling the system queues: it gets slower but stays correct.

### What the numbers say, and what isn't known yet

- **Measured:**
  - The ceiling on this laptop is about 55 to 80 transfers per second.
  - Up to 10 req/s the p99 is far inside the target. At 50 req/s it is already above it.
  - At 100 req/s, platform threads did better than virtual threads.
- **Not measured yet: where the time goes.** A likely suspect is the Hikari pool of 10 connections, shared by
  everything a transfer does in its own transactions: JIT lookup, idempotency claim, transfer, response store. It is
  also shared by the outbox and notification listeners after each commit.
  - With virtual threads, every one of up to 400 in-flight requests can be running and waiting for a connection at
    once. With platform threads, Tomcat admits at most 200, and the rest wait in the accept queue.
  - That would fit the virtual-threads result, but it is a hypothesis, not a measurement.
- **Next (rest of M13):**
  - Repeat with Hikari pool sizes 10 / 20 / 40, watching `hikaricp_connections_pending` (Grafana, `obs` profile).
  - `EXPLAIN ANALYZE` on the transfer path's queries: limit sums, idempotency claim, wallet locks.
  - Re-measure here with the same script.
- **The load generator shares the laptop.** k6 (up to 400 VUs), the app and all containers share 12 hardware threads,
  so these are numbers for one laptop running everything, not for the app alone.
