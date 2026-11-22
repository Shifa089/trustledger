# Performance notes

Measured numbers only. Each section says how the numbers were produced so they can be reproduced. Load-test
results (k6, throughput, p95/p99, virtual threads on/off) are added in M13.

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
