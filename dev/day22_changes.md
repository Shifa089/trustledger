# Changes

## DAY22 (M11, FR-S-1): transaction history, history indexes and a Redis cache for reference data

**Goal** (SPEC FR-S-1 §2, wallet endpoints §5, §8 M11):
1. a `reporting` module with `GET /api/v1/wallets/me/transactions`: transfers (in and out) and top-ups together;
   filters `from`, `to`, `type`; pagination with a whitelist of sort fields (unknown → 400); newest first by
   default; an optional cursor (keyset) mode;
2. indexes through Flyway, and `EXPLAIN ANALYZE` of the main query recorded in `docs/perf.md`;
3. Redis `@Cacheable` with TTLs for the limits configuration and ledger account lookups **only**, with a comment and
   a test showing that balances are never cached.

The monthly PDF statement (FR-S-2, the rest of M11) is not part of Day 22.

Branch: `feature/day22-transaction-history` (created from `master`).

Decisions (approved in the plan):
- **Redis is used only on paths that hold no locks.** Transfer, top-up settlement and held-transfer approval read
  limits and resolve ledger accounts while the wallet rows are locked. CLAUDE.md forbids remote calls there, so those
  paths keep reading PostgreSQL. The caches serve `GET /wallets/me` and `LedgerApi.balanceOf` (the account lookup
  only; the sums are never cached).
- **New dependency `spring-boot-starter-cache`**, with ADR 0019 and a line in the CLAUDE.md stack.
- **What the history shows:**
  - the sender sees their transfers in every status;
  - the receiver sees a transfer only once the money arrived (COMPLETED or REVERSED);
  - top-ups show in every status except CREATED.

---

### 1. Payments: the read model behind the history

Reporting may not read the payments tables, and a Specification works on one entity. So payments exposes one
read-only view and a query API:

- **`V25__payments_create_wallet_transactions_view.sql`**: `payments.wallet_transactions`, a `UNION ALL` with one row
  per (wallet, transfer or top-up):
  - `TRANSFER_OUT`: the sender's row;
  - `TRANSFER_IN`: the receiver's row;
  - `TOPUP`.

  Columns: `wallet_id`, `source_id`, `type`, `status`, `amount_minor`, `currency`, `counterparty_wallet_id`, `note`,
  `created_at`, `visible`. The visibility rule is the `visible` column, not a `WHERE` on each branch; section 3
  explains why.
- **Domain:** `WalletTransaction` (an `@Immutable` entity on the view, `@IdClass` `WalletTransactionId` =
  wallet + source, because a transfer appears once for each side), and the `WalletTransactionRepository` port.
- **Infrastructure:** `JpaWalletTransactionRepository` (`JpaSpecificationExecutor`). `findFirst` uses the fluent
  `findBy(...).limit(n)`, so the cursor mode runs no count query.
- **Application:**
  - `WalletTransactionSpecs`:
    - `matching(filter)`: wallet, **always `visible`**, `from`/`to`, `type`;
    - `olderThan(cursor)`: the keyset predicate `created_at <= c AND (created_at < c OR (created_at = c AND id < id_c))`;
    - `orderOf(sort)`: always ending with `created_at DESC, source_id DESC` as tie-breakers.
  - `WalletTransactionQueryService` with `page(...)` (offset paging with totals) and `after(...)`, which reads
    `size + 1` rows to know `hasNext`.
- **Public API (`PaymentsApi`):** `transactions(filter, sort, page, size)` and
  `transactionsAfter(filter, cursor, size)`, with the records `WalletTransactionRecord` (its `toString` leaves out
  the note), `WalletTransactionType`, `TransactionFilter`, `TransactionSort` (the enum is the type-level whitelist:
  `CREATED_AT`, `AMOUNT`), `TransactionCursor`, `TransactionPage` and `TransactionSlice`.

### 2. Reporting module (new)

`com.trustledger.core.reporting` owns no tables. It depends only on `PaymentsApi`, `WalletApi` and `IdentityApi`.

- **`TransactionHistoryController`**: `GET /api/v1/wallets/me/transactions`, `@PreAuthorize("hasRole('CUSTOMER')")`.
  The wallet comes from the token, never from a parameter (BOLA).
  - Query parameters: `from`, `to`, `type`, `page`, `size`, `sort`, `cursor`. They are taken as strings, so every
    bad value gives the same 400 ProblemDetail with `errorCode=VALIDATION_FAILED`.
  - Response: `{items, page, size, totalElements, totalPages, nextCursor}`.
  - Each item: `{id, type, status, amount:"1200.50", currency, counterpartyWalletId, note, createdAt}`.
- **`TransactionHistoryService`** checks every parameter before any database read:
  - `from`/`to`: `yyyy-MM-dd` days in **Asia/Kolkata**, both included. The range is
    `[from 00:00 IST, to + 1 day 00:00 IST)`, so `to=2026-11-30` covers the whole Indian day. `from > to` → 400.
  - `type`: `TOPUP`, `TRANSFER_IN` or `TRANSFER_OUT`.
  - `page`/`size`: the `trustledger.paging` bounds (size above 100 → 400, as on the review queues).
  - `sort`: through `HistorySort`.
  - `cursor`: `?cursor=` (empty) starts cursor mode; then pass each response's `nextCursor`. Cursor mode is always
    newest first, so `cursor` with `page` or `sort` → 400.
  - No wallet yet → 403 `KYC_REQUIRED`.
- **`HistorySort`**: `createdAt` or `amount`, optionally `,asc` or `,desc`. One field only; anything else → 400.
- **`HistoryCursor`**: opaque base64url of `v1|<createdAt>|<id>`. A malformed cursor → 400. A forged cursor can't
  reach other rows, because the query is still limited to the caller's wallet.
- **Security:** `/api/*/wallets/**` was already CUSTOMER-only in `SecurityConfig`. `SecurityMatrixIT` has 5 new rows:
  anonymous 401; no role, officer, admin and auditor 403.

### 3. Indexes and `docs/perf.md`

- **`V26__payments_index_history.sql`** adds `id` as the last column of each per-wallet index:
  - `(sender_wallet_id, created_at, id)`;
  - `(receiver_wallet_id, created_at, id)`;
  - `topups (wallet_id, created_at, id)`.

  These replace the V11, V22 and V13 indexes. The old indexes were a prefix of the new ones, so the limit sums and
  the data export still use them. Migration tests were updated for the new names.
- **`TransactionHistoryQueryPlanIT`** seeds 60,000 history rows (one wallet with 10,000), runs
  `EXPLAIN (ANALYZE, BUFFERS)` for every query shape, fails on any `Seq Scan` of transfers or top-ups, and writes the
  plans to `core-app/target/perf/history-explain.txt`.
- **Finding, from the measured plan:**
  - The first version of the view had `WHERE status ...` on two branches. PostgreSQL then can't flatten the
    `UNION ALL`, so those branches were planned as separate subqueries and **sorted all of the wallet's rows**.
  - Page 0 cost 3.09 ms and 148 buffers.
  - With the rule moved into the `visible` column (always filtered in `matching`), all three branches use
    `Index Scan Backward` inside a `Merge Append`: **0.066 ms and 12 buffers**.
- **Keyset vs offset at depth 9,000:** cursor **0.069 ms / 11 buffers**; `OFFSET 9000` 9.6 ms / 336 buffers (it reads
  and sorts 9,020 rows), plus a 2.5 ms count. The full table and plans are in `docs/perf.md`.

### 4. Redis cache (ADR 0019)

- **`shared/cache`**:
  - `CacheNames`: exactly two caches, `wallet-limits` and `ledger-accounts`;
  - `CacheProperties` (`trustledger.cache.ttl.*`): every cache needs a positive TTL, and an unknown name such as
    `wallet-balances` stops startup;
  - `CacheConfig`:
    - `@EnableCaching`, string keys under `tl:cache:`, no `null` values;
    - `disableCreateOnMissingCache()`: no other cache can be created;
    - **immediate writes**;
    - fail-open `LoggingCacheErrorHandler`.
- **`wallet-limits`** (TTL 1 h): `TierLimitsCatalog.forTier(tier)`, used by `GET /wallets/me`.
  - The key is `<tier>:<fingerprint>`, a SHA-256 of `trustledger.limits`. After a deploy with new limits, Redis
    can't serve the old ones until a TTL ends: new limits mean new keys.
  - `WalletApi.limitsFor` (under locks) stays uncached; a comment says why.
- **`ledger-accounts`** (TTL 24 h): `LedgerAccountDirectory.findByCode(code)` returns a `LedgerAccountRef` record (ID,
  code, type, normal side, currency), never the entity and never a balance.
  - A missing code is not cached.
  - `LedgerAccountService.balanceOf` uses it to find the account, then sums the journal lines on every call.
  - Posting (under locks) still resolves accounts with its own query.
- **Each module registers its own cache** (`WalletCacheConfig`, `LedgerCacheConfig`) with a typed
  `JacksonJsonRedisSerializer`, so `shared` knows no module types.
- **Balances never cached**, as both comments and tests:
  - comments on `CacheNames`, `CacheConfig`, `LedgerAccountService.balanceOf`/`balancesOf`, `WalletQueryService.mine`
    and `WalletApiImpl.limitsFor`;
  - `NoBalanceCachingTest` (ArchUnit) and `BalanceNotCachedIT` (section 5).
- **Second finding: async cache writes.** `RedisCacheIT` passed alone but failed in the full suite: the key wasn't in
  Redis when checked, and appeared a moment later. Spring Data Redis 4's `DefaultRedisCacheWriter.put` **writes
  asynchronously** on Lettuce, and a failed async write never reaches the `CacheErrorHandler`. Fixed with
  `RedisCacheWriter.create(factory, w -> w.immediateWrites())`. The test now starts from a fresh account and uses
  `evictIfPresent`, which is immediate, instead of `evict`, which may be deferred.
- `application.yml`: `spring.cache.type: redis`; `trustledger.cache.ttl.wallet-limits: 1h` and `ledger-accounts: 24h`.

### 5. Tests

**Unit tests:**

| Test | What it covers |
| --- | --- |
| `HistorySortTest` | the default; whitelisted fields with and without a direction; 10 refused forms (unknown field, entity field name, bad direction, two fields, an injection attempt) |
| `HistoryCursorTest` | round trip with microseconds; garbage; wrong version or shape |
| `TransactionHistoryServiceTest` | default page; IST day bounds (UTC 18:30); a single day; `from > to`; bad dates and types; page and size bounds; unknown sort; cursor with page or sort; empty cursor → next cursor; last slice; no wallet → `KYC_REQUIRED`; bad input reads nothing |
| `CachePropertiesTest` | valid TTLs; missing, zero, negative and unknown cache names |
| `TierLimitsCatalogTest` | equal configurations give equal fingerprints; changed limits change the key |
| `NoBalanceCachingTest` (ArchUnit) | only `TierLimitsCatalog` and `LedgerAccountDirectory` may be cached; nothing that returns `AccountBalance`, `WalletView` or `MyWalletView`, or is named `*balance*`, is cached; no class-level caching; cached values have no balance field |
| `LedgerAccountServiceTest` | constructor updated |

**Integration tests (Testcontainers):**

| Test | What it covers |
| --- | --- |
| `TransactionHistoryIT` (29) | an empty history; in, out and top-ups combined, newest first, decimal amounts; the sender sees all 6 statuses, the receiver 2; a CREATED top-up hidden; another customer's rows never returned; no wallet → 403; each type filter; Indian-day boundaries at 18:29:59 and 18:30:00 UTC; an empty month; 15 bad parameter combinations → 400; sort by amount and createdAt in both directions; page boundaries (first, last, past the end, size 100); the cursor walk over rows with equal timestamps matches offset order with no gaps or duplicates; cursor plus filters; rows inserted mid-walk don't shift it |
| `TransactionHistoryQueryPlanIT` (8) | the `EXPLAIN` checks above |
| `RedisCacheIT` (5) | only 2 caches exist; a lookup is cached with its TTL and holds JSON without a balance; a platform account cached after an immediate eviction; no negative caching; limits cached under the fingerprint |
| `BalanceNotCachedIT` | `GET /wallets/me`, `balanceOf` and `balancesOf` show each top-up on the very next read; afterwards Redis holds only limit and account keys, with no balance in them |
| `SecurityMatrixIT` | 5 new rows |
| `PaymentsMigrationIT`, `TopupsMigrationIT`, `PrivacyMigrationIT` | the V26 index names |

### 6. Docs

- `docs/perf.md` (new): method, indexes, the before/after finding, results table, what the numbers mean.
- `docs/adr/0019-redis-cache-for-reference-data.md` (new).
- `CLAUDE.md`: stack line `Spring Cache on Redis via spring-boot-starter-cache (ADR 0019; never balances)`.

### 7. Verification status

- core-app unit tests: **1290 passed, 0 failed**, including `ModularityTests` and `ArchitectureRulesTest`.
- Integration tests run on this branch, all green after the fixes: `TransactionHistoryIT`,
  `TransactionHistoryQueryPlanIT`, `RedisCacheIT`, `BalanceNotCachedIT`, `WalletControllerIT`, `SecurityMatrixIT`
  (192), `LedgerAccountServiceIT`, and the three migration ITs.
- **The full `./mvnw verify` did not finish here.** Claude Code stopped it because the machine was low on memory.
  Up to that point, the only failure was the `RedisCacheIT` async-write problem fixed above. The remaining IT classes
  (`shared.*` after `cache`, `wallet.*`) and `risk-aml-service` have not been run on this branch. **Run
  `./mvnw verify` before merging.**

### What to learn from it

- **Measure the plan; don't assume it.** Adding the right index wasn't enough: one innocent `WHERE` inside a
  `UNION ALL` view made PostgreSQL sort every row of two branches. `EXPLAIN (ANALYZE, BUFFERS)` on realistic data
  showed it at once, and a test now keeps it from coming back.
- **Offset vs keyset.** `OFFSET n` reads and throws away n rows and needs a `COUNT(*)`; a cursor `(created_at, id)`
  seeks straight to the position. Keyset needs a unique tie-breaker (the ID) in both the `ORDER BY` and the
  predicate, plus the redundant `created_at <= c` so the seek becomes an index condition.
- **A sort whitelist belongs in types:** a `Field` enum mapped from API names, instead of passing strings through to
  `Sort.by(...)`.
- **Caching is a correctness decision first.** What may go stale (never balances); how staleness after a deploy is
  handled (a configuration fingerprint in the key); where a cache must not be called (under row locks); what happens
  when it is down (fail open). Each of these is enforced by a test, not just a comment.
- **Read the library's defaults.** Spring Data Redis 4 writes cache entries asynchronously. That showed up only as a
  test that failed depending on test order. The fix was one configuration line, and the lesson is to treat "passes
  alone, fails in the suite" as a real signal about shared state or timing.
