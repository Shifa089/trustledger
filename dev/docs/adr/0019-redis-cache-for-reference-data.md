# ADR 0019: Redis cache (`spring-boot-starter-cache`) for reference data only; balances are never cached

- Status: Accepted
- Date: 2026-11-22
- Spec: docs/SPEC.md §8 M11 ("Redis cache for rarely changing data (limits configuration, ledger account lookups).
  Never cache balances."); CLAUDE.md Stack (changed by this ADR) and Architecture rules ("No remote calls while
  holding locks")

## Context

M11 asks for `@Cacheable` with Redis for two kinds of rarely changing data: the wallet limits configuration and
ledger account lookups. It also says balances must never be cached.

Redis is already in the stack (Bucket4j rate limits, `spring-boot-starter-data-redis`). Spring Boot 4 split cache
auto-configuration (the `RedisCacheManager`, `spring.cache.*`, `RedisCacheManagerBuilderCustomizer`) into its own
module, so `@EnableCaching` with Redis needs `spring-boot-starter-cache`. It is a new artifact, hence this ADR.

Two risks shape the design:

1. **A stale balance is a money bug.** A balance served from a cache could let a customer spend money twice, show a
   wrong amount, or hide a wallet/ledger mismatch from reconciliation.
2. **A Redis round trip is a remote call.** The transfer, top-up settlement and held-transfer approval flows read the
   tier limits and resolve ledger accounts *while the wallet rows are locked*. CLAUDE.md forbids remote calls under
   those locks: with the 500 ms Redis timeout, a slow Redis would stretch every lock and serialize money movement
   behind it.

## Options

1. **`spring-boot-starter-cache` + `RedisCacheManager`, used only on lock-free read paths.** Money paths keep reading
   from PostgreSQL in their own transaction.
2. **Cache everywhere, including the money paths.** More hits, but up to one Redis timeout per lookup while row locks
   are held, against an explicit architecture rule.
3. **A local in-memory cache (Caffeine) instead of Redis.** No network hop, so it could be used under locks, but it is
   a new library the spec doesn't ask for, and each instance would hold its own copy.
4. **No cache.** The data is small and cheap to read; but M11 asks for it, and it is a useful, safe pattern to have.

## Decision

Option 1.

- **Exactly two caches exist** (`CacheNames`): `wallet-limits` and `ledger-accounts`. The cache manager is built with
  `disableCreateOnMissingCache()`, so an `@Cacheable` on any other name fails instead of quietly caching something
  new. Each cache must have a positive TTL in `trustledger.cache.ttl.*` (startup fails otherwise; an unknown name
  such as `wallet-balances` is refused too).
- **`wallet-limits`** (`TierLimitsCatalog.forTier`, TTL 1 h): the limits come from `trustledger.limits.*`, so they
  change only with a redeploy. A plain TTL would keep serving the *old* limits from Redis after a deploy with new
  ones, so the key is `<tier>:<fingerprint>`, where the fingerprint is a SHA-256 of the limits configuration. New
  limits mean new keys; old entries age out. Used by `GET /wallets/me`. `WalletApi.limitsFor`, which payments calls
  under the wallet locks, stays uncached.
- **`ledger-accounts`** (`LedgerAccountDirectory.findByCode`, TTL 24 h): an account row never changes after it is
  opened (the entity is `@Immutable`, there is no delete). The value is a `LedgerAccountRef` record (ID, code, type,
  normal side, currency), never the JPA entity and never a balance. A missing code is not cached, so an account
  opened right after a miss is found. Used by `LedgerAccountService.balanceOf` to find the account; the balance is
  then summed from the journal lines on every call. Posting, which runs under the wallet locks, keeps resolving its
  accounts with one PostgreSQL query.
- **Values are typed JSON** (`JacksonJsonRedisSerializer<T>` per cache), keys are strings under `tl:cache:`, `null`
  is never cached.
- **Fail open:** a `LoggingCacheErrorHandler` logs a Redis error and lets the method run against PostgreSQL. A Redis
  outage makes these reads slower, never failing.
- **Immediate writes** (`RedisCacheWriter.create(factory, w -> w.immediateWrites())`). Spring Data Redis 4 otherwise
  writes cache entries asynchronously on Lettuce: the call returns before Redis has the value, and a failed write is
  fire-and-forget, never reaching the error handler. Found on Day 22 when `RedisCacheIT` passed alone but failed in
  the full suite (the key appeared a moment after the assertion). The cost is one Redis round trip per cache miss,
  on lock-free read paths only.

## Consequences

- CLAUDE.md Stack lists `spring-boot-starter-cache` (ADR 0019).
- **Balances are never cached, and this is enforced, not just documented:**
  - `NoBalanceCachingTest` (ArchUnit): only `TierLimitsCatalog` and `LedgerAccountDirectory` may have `@Cacheable`
    or `@CachePut` methods; no method that returns `AccountBalance`, `WalletView` or `MyWalletView`, or has
    "balance" in its name, is cached; no class-level caching annotation; cached values carry no balance field.
  - `BalanceNotCachedIT`: read the wallet and ledger balances, move money, read again; the new balance shows at once,
    and Redis holds only limits and account keys with no balance in them.
  - Comments on `LedgerAccountService.balanceOf` / `balancesOf`, `WalletQueryService.mine`, `WalletApiImpl.limitsFor`
    and `CacheNames` say why.
- The cache saves little today (both lookups are cheap). The value is the pattern: what may be cached, how staleness
  after a deploy is handled, and how a cache stays out of locked sections.
- Adding a cache later means: add its name to `CacheNames`, a TTL to the configuration, a typed
  `RedisCacheManagerBuilderCustomizer` in the owning module, and the method to the whitelist in
  `NoBalanceCachingTest`, which makes the decision visible in review.
