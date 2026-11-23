# Changes

## DAY24 Session A (M13): perf seed, k6 transfer load test, measured results, virtual threads on vs off

**Goal** (SPEC §2 non-functional requirements, §8 M13):
1. A `perf` profile seeding 200 FULL-tier wallets with balances and matching ledger entries, plus a token strategy.
2. `load-tests/transfers.js` (k6):
   - tokens fetched once in `setup()`;
   - random sender/receiver pairs;
   - a unique `Idempotency-Key` on every request;
   - stages of 10, 50 and 100 req/s, 2 minutes each;
   - thresholds `p(99)<300 ms` and errors `<1%`.
3. Run it, write `docs/perf.md` from the real output only, and compare virtual threads on vs off at 100 req/s.

Rest of M13, for a later session: Hikari pool sizes, `EXPLAIN ANALYZE` indexes, `docs/security-checklist.md`,
Trivy in CI.

Branch: `feature/day24a-perf` (from `master`, which has Day 23 Session B).

Decisions (approved in the plan):
- **Seed with an `ApplicationRunner` in profile `perf`, not a Flyway repeatable migration.** An `R__` script would be
  recorded in `flyway_schema_history` of the shared local database. Starting the app without `perf` afterwards would
  then fail Flyway validation ("applied migration not resolved locally").
- **Real Keycloak tokens, fetched once.** A perf-only JWT decoder would skip RS256 verification and flatter the
  numbers. A token per request would measure Keycloak, not the app.
- **Fixed Keycloak user IDs.** Each perf user's Keycloak ID is the seeded `keycloak_subject`, so JIT provisioning
  finds the seeded user and wallet, and nothing has to be looked up or wired at run time.
- **No new dependency.** k6 is an external CLI, not a build dependency.

---

### 1. Seed data (`core-app`, package `shared.perf`, profile `perf` only)

| Class | What |
| --- | --- |
| `PerfSeedIds` | Deterministic IDs: `<kind>-0000-4000-8000-<n, 12 digits>`, one kind each for subject, user, wallet, ledger account, journal entry, the two lines, the MIN and FULL KYC cases. Usernames `perf001`..`perf999`. Fake maker and checker IDs. |
| `PerfSeedProperties` | `trustledger.perf.seed.wallets` (2..999) and `opening-balance` (positive INR `Money`), validated in the constructor. |
| `PerfSeeder` | One `TransactionTemplate`. For n = 1..count: user (no personal data), approved MIN and FULL KYC cases (maker ≠ checker), FULL ACTIVE wallet, `WALLET:<id>` ledger account, opening entry (debit `PLATFORM:GATEWAY_CLEARING`, credit the wallet, `TOPUP` / reference type `PERF_SEED`). |
| `PerfSeedConfig` | `@Profile("perf")`: the seeder bean and an `ApplicationRunner` (runs after Flyway). |

- **Idempotent.** A wallet that exists is skipped, and its ledger rows are written only in the run that created it.
  So a restart never resets a balance that transfers have moved, and never posts a second opening entry.
- **An already provisioned user is reused.** If the subject was provisioned before (someone logged in as `perf001`
  first), the seeder looks up that user's ID and uses it.
- **SQL straight into the module tables.** It works like V7's platform accounts and depends on no business module.
  `shared` must never depend on one, so the clearing account is found by its code in SQL. The real path (KYC
  maker-checker, events, mails, audit) would be slow, and it would fill the audit trail with fake reviews.
- **`application-perf.yml`:**
  - 200 wallets × ₹1,00,000.
  - `trustledger.rate-limit.money-per-minute: 600`. At 100 req/s over 200 random senders, each user sends about 30
    transfers a minute, above the production 20. The limiter stays on, so its Redis call is still measured.

### 2. Keycloak users and token strategy (`load-tests/keycloak-perf-users.sh`)

- Admin REST `partialImport` with `ifResourceExists: SKIP`. Running it again is safe: the second run gave
  `added 0, skipped 201`.
- Client `trustledger-perf`, LOCAL ONLY like `trustledger-dev`: public, password grant only, audience mapper
  `trustledger-api`, `access.token.lifespan` 1800 s. The realm default is 300 s, shorter than a run.
- Users `perf001`..`perf200`: role CUSTOMER, password `TRUSTLEDGER_PERF_USER_PASSWORD` (new in `.env.example`), fixed
  `id` = `PerfSeedIds.subject(n)`.
- **It checks the IDs afterwards.** The script reads `perf001` and the last user back and fails if Keycloak didn't
  keep the IDs. Verified against Keycloak 26.8.2: `partialImport` keeps them.
- Refuses any Keycloak URL but localhost, and never prints a secret or a token.

### 3. k6 script (`load-tests/transfers.js`) and `load-tests/README.md`

- **`setup()`:**
  - 200 password grants, in parallel batches of 20.
  - Checks the token lifetime (at least 900 s).
  - Checks that `GET /wallets/me` as `perf001` returns the seeded wallet. A wrong profile fails at once, not after
    6 minutes.
- **Each iteration:**
  - a random sender and a random receiver, never the same wallet;
  - ₹1.00–₹100.00;
  - `Idempotency-Key: crypto.randomUUID()`;
  - tag `name:transfer`, so the token calls aren't measured;
  - checks `201` and `COMPLETED`.
- **Stages:** three `constant-arrival-rate` scenarios, `rps10`, `rps50`, `rps100`, 2 minutes each, 10 s apart.
  `MODE=steady` runs only 100 req/s (or `RATE`) for the virtual-threads comparison.
- **Thresholds:**
  - overall, on the `name:transfer` sub-metrics: `p(99)<300`, `rate<0.01` and `checks>0.99`;
  - the same per stage, so the summary shows p95/p99 and errors for each load level.
- **README:** setup, run commands, the VT on/off commands, and why:
  - k6 runs natively and not in Docker (the token issuer would be `host.docker.internal`);
  - risk-aml-service stays off (rule R4 would freeze every wallet within seconds).

### 4. Measured results (`docs/perf.md` §2, raw output in `docs/perf-results/`)

Laptop: i5-13420H (8C/12T), 15.7 GB RAM, Windows 11. k6, the app and all containers ran on the same laptop. One run
per configuration.

| Run | Completed / attempted | Throughput | p95 | p99 | Errors |
| --- | --- | --- | --- | --- | --- |
| Ramp 10 req/s | 1,200 / 1,200 | 10 /s | 61.18 ms | **82.26 ms** | 0% |
| Ramp 50 req/s | 6,001 / 6,000 | 50 /s | 250.78 ms | **396.66 ms** | 0% |
| Ramp 100 req/s | 8,305 / 12,000 | ≈69 /s | 7.75 s | **9.07 s** | 0% |
| Steady 100, virtual threads on | 7,263 / 12,000 | 55.6 /s | 11.41 s | **14.81 s** | 0% |
| Steady 100, virtual threads off | 10,210 / 12,000 | 79.9 /s | 5.77 s | **6.49 s** | 0% |

- **The SPEC target (p99 < 300 ms at 100 req/s) is not met.** The ceiling on this laptop is about 55 to 80 transfers
  per second.
- **Correctness held.** After 33,348 transfers:
  - the 200 balances still sum to exactly 200 × ₹1,00,000;
  - 0 wallets disagree with the ledger;
  - 0 journal entries are unbalanced;
  - 0 failed requests and 0 deadlocks.
- **Virtual threads off did better** in this pair of runs: 41% more throughput, and p99 less than half. `docs/perf.md`
  calls the cause a hypothesis, not a measurement: the default Hikari pool of 10 connections, with up to 400 virtual
  threads waiting on it at once. Testing it (pool sizes, `hikaricp_connections_pending`, `EXPLAIN ANALYZE`) is the
  next M13 step.

### 5. Tests

- `PerfSeedIdsTest` (unit, 6):
  - the IDs are deterministic and distinct across all kinds × 200;
  - numbers out of range are refused;
  - property validation;
  - `transfers.js` and `keycloak-perf-users.sh` build the same subject and wallet IDs, client and usernames, and keep
    the idempotency key and both thresholds. It reads the files like `GrafanaDashboardTest`.
- `PerfSeederIT` (Testcontainers, 3, ordered, 5 wallets so the shared test database stays small):
  - the seed creates FULL ACTIVE wallets with the opening balance, an approved MIN and FULL case, and one opening
    entry, and each wallet's balance equals its ledger balance;
  - a seeded subject gets its seeded wallet from `GET /wallets/me` through the production filter chain, and a
    transfer completes;
  - a second seed creates nothing, keeps the moved balance and posts no second opening entry;
  - seeding one more wallet later adds only that one.

### 6. Verification status

`./mvnw verify` **passed** on this branch, the whole reactor:

| Module | Unit tests | Integration tests |
| --- | --- | --- |
| core-app | 1312 (+6) | 801 (+3) |
| risk-aml-service | 107 | 58 |
| event-contracts | 36 | |

`ModularityTests` and `ArchitectureRulesTest` are green.

Manual run checks:
- the Keycloak script ran twice (`added 201`, then `skipped 201`) and the IDs matched;
- the seed logged `200 of 200 wallets created` on the first start and `0 of 200` on every later start;
- a 20 s smoke run of the script: 369 transfers, 0 errors;
- the five measured runs above, then the drift check in PostgreSQL.

### What to learn from it

- **An open-model load test tells the truth about a ceiling.** `constant-arrival-rate` keeps starting requests at the
  target rate even when the app falls behind, and reports the ones it couldn't start as `dropped_iterations`. A
  closed model (fixed VUs looping) would slow down with the app and hide the problem behind a lower request rate.
  Always read throughput next to latency: "p99 9 s at 69/s" says the 100 req/s target wasn't even offered in full.
- **Correctness under load is a separate check.** Latency failed the target, but the money didn't drift. Sum the
  balances, compare each wallet with its ledger, and look for unbalanced entries after every load test. That's the
  SPEC's first non-functional requirement, and the one a fintech can't trade away.
- **Virtual threads remove the thread limit, not the other limits.** With platform threads, Tomcat's 200 threads
  hold the excess back. With virtual threads, every in-flight request runs and waits on the next bounded resource:
  a 10-connection pool, Redis, row locks. Measure before assuming virtual threads are faster, and size the real
  bottleneck.
- **Load-test data must match the security model, not bypass it.** Fixed Keycloak IDs equal to the seeded subjects
  let real, verified tokens hit real seeded wallets. No test-only decoder, no shortcut that makes the numbers look
  better than production.
- **Seed data idempotently, without touching migration history.** Deterministic IDs and "write the ledger only in the
  run that created the wallet" keep restarts safe. A profile-only runner leaves `flyway_schema_history` the same for
  every profile.
- **Report what you measured, and label what you didn't.** One run per configuration, k6 on the same laptop, and a
  bottleneck hypothesis: `docs/perf.md` says all three plainly instead of rounding them into a conclusion.
