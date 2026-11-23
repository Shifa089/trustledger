# Changes

## DAY25 Session B (M16): README, ADR check, demo profile, complete http/ collection

**Goal** (SPEC §8 M16, §11):
1. Rewrite `README.md` with the 10-part structure from §11:
   - a Mermaid architecture diagram, the compliance table, the highlights, the stack;
   - run locally in 3 commands;
   - tests and coverage from the latest JaCoCo report;
   - links to `docs/perf.md` and `docs/security-checklist.md`, and every ADR linked;
   - a "Not yet built" list: AWS deployment, PDF statements, Envers, AI case summary.
2. Check that ADRs 0001–0009 exist and match the code.
3. Add a `demo` profile that seeds customer1/customer2 with FULL KYC and balances.
4. Complete the `http/` collection for every endpoint in §5.

Not done here (rest of M16): the demo video and `docs/demo-script.md`. The README has a placeholder for the video
link.

Branch: `feature/day25b-portfolio` (from `master`, which has Day 25 Session A).

Decisions:
- **customer1 and customer2 get fixed Keycloak IDs** in `infra/keycloak/realm-trustledger.json`
  (`de300001-0000-4000-8000-00000000000n`), the same approach as the perf users (M13):
  - The seed has to know the token's `sub` before anyone logs in. With random IDs, the seeded wallet would belong to
    nobody.
  - **Cost:** the realm file applies only when the Keycloak container is created, so an existing local Keycloak needs
    `--force-recreate keycloak` once. That also removes the script-created perf users:
    `load-tests/keycloak-perf-users.sh` puts them back.
- **The seed SQL is shared, not copied.** The perf seeder's per-wallet SQL moved into
  `shared.seed.FullWalletSeeder`, which `PerfSeeder` and the new `DemoSeeder` both use. `PerfSeeder` behaves exactly
  as before: same IDs, and `PerfSeederIT` still passes.
- **The compose profile `apps` runs core-app with `local,demo`.** The three README commands then end in a passing
  smoke test with the default users. The frozen customer1 problem from Day 25 A is gone for anyone on the new realm.
- **Balances: customer1 ₹50,000, customer2 ₹10,000.** That leaves room for the AML demo's 3 × ₹46,000 top-ups under
  the ₹2,00,000 FULL cap; a unit test checks it against `application-demo.yml`.
- **Not-built endpoints are in the collection too**, labelled "NOT BUILT YET" with the answer they give today
  (checked live): statements 404, ledger balance 404 for an auditor (the security rule exists, the handler doesn't),
  `/aml/alerts` and `/ai-summary` 403 (deny-all).
- **A guard test instead of a promise:** `HttpCollectionTest` (core-app and risk-aml-service) fails the build when a
  controller endpoint has no request in `http/`.
- **ADRs: notes, not rewrites.** The decisions still hold. Each ADR 0001–0009 gets a dated "Code check" section that
  confirms the named classes and settings and lists what an earlier milestone promised but didn't deliver.
- No new dependency, no migration, no new endpoint.

---

### 1. `demo` profile (core-app)

| File | What |
| --- | --- |
| `shared/seed/SeedWallet.java` | Record of every ID one seeded wallet needs (subject, user, KYC cases, wallet, account, entry, lines, reference type, maker, checker). Maker ≠ checker is enforced |
| `shared/seed/FullWalletSeeder.java` | The SQL moved out of `PerfSeeder`: a user with no personal data, approved MIN and FULL KYC, a FULL ACTIVE wallet, its `WALLET:<id>` account and a balanced opening entry (Dr `PLATFORM:GATEWAY_CLEARING` / Cr wallet). Idempotent: an existing wallet keeps its balance, and its ledger rows are written only in the run that created it |
| `shared/perf/PerfSeeder.java` | Now delegates to `FullWalletSeeder`; same IDs, same behaviour |
| `shared/demo/DemoSeedIds.java` | Fixed IDs `de30000<kind>-0000-4000-8000-<n>`, separate from perf's `0e7f…` |
| `shared/demo/DemoSeedProperties.java` | `trustledger.demo.seed.customer1-balance` / `customer2-balance`, positive INR or startup fails |
| `shared/demo/DemoSeeder.java`, `DemoSeedConfig.java` | `@Profile("demo")`: an `ApplicationRunner` seeds both customers in one transaction after Flyway; logs "Demo seed: n of 2 created" |
| `resources/application-demo.yml` | ₹50,000 and ₹10,000 |
| `infra/keycloak/realm-trustledger.json` | `"id"` for customer1 and customer2 |
| `infra/docker-compose.yml` | core-app in profile `apps`: `SPRING_PROFILES_ACTIVE: local,demo` |

The seeded users hold no personal data. `/me` shows e-mail and name as `null`, and transfers to them go by wallet ID.
That is deliberate: the seed code never touches encryption keys or personal data. It writes no audit rows either
(test data, not an actor's action). ADR 0008's code-check note says so.

Usage: `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local,demo`, or the compose profile `apps`.

### 2. `http/` collection: every §5 endpoint

New files:

| File | Covers |
| --- | --- |
| `demo.http` | The tour with the demo seed: tokens, `/me`, `/kyc/me` (FULL), wallets, a ₹1,500 transfer, its replay (same transfer, `Idempotent-Replayed`), same key with another body (422), the receiver reads it, history, insufficient funds (422) |
| `history.http` | `GET /wallets/me/transactions`: first page, type and date filters with sort, keyset cursor and next slice, bad size (400), officer (403); `GET /wallets/me/statements/{yyyy-MM}` (**not built**: 404) |
| `audit.http` | `GET /audit/verify`, `GET /audit/events` (page, action and time window, by entity), customer 403, no token 401; `GET /admin/ledger/accounts/{code}/balance` (**not built**: 404) |
| `aml-cases.http` | The close branch: list (oldest first), filter, detail, close while OPEN (409), assign, close without notes (400), close as false positive, escalate a closed case (409), STR of a closed case (409), customer 403; `GET /aml/alerts` and `POST /aml/cases/{id}/ai-summary` (**not built**: 403) |
| `README.md` | How to set up the collection, which file to start with, and a table of **every SPEC §5 endpoint → file** |

Changed:
- `topups.http`: `POST /webhooks/razorpay` with a wrong signature and without one (both 400
  `WEBHOOK_SIGNATURE_INVALID`), and how to send a validly signed one (checkout through a tunnel, or
  `scripts/aml-demo-webhooks.sh capture`). The collection never holds the webhook secret.
- `auth.http`: the "transfer stub → 501 NOT_IMPLEMENTED (until M6)" request was out of date since M6. It's now a
  bodiless transfer (400, no money moves) for showing the rate limit.

### 3. Guard: `HttpCollectionTest` (core-app 2 tests, risk-aml-service 2 tests)

- Scans the main-code `@RestController`s and collects every `METHOD /path` mapping. Test probes such as
  `SecurityProbeController` are skipped.
- Requires a request line `METHOD {{api}}/api/v1<path>` (`{{aml}}` for the AML service) in some `http/*.http` file.
  Any value may stand in for a path variable, and a query string is allowed.
- A second test proves the guard notices a missing request.
- Mutation check: with `audit.http` moved away, the core-app test failed with
  `["GET /audit/verify", "GET /audit/events"]`.

### 4. Tests for the demo seed

- `DemoSeedIdsTest` (6, unit):
  - the IDs are deterministic and distinct, and none collides with the perf IDs;
  - only customers 1 and 2 exist;
  - **the realm file gives customer1/customer2 exactly the seeded IDs**;
  - balances must be positive rupees;
  - `application-demo.yml` binds, and customer1's balance + 3 × ₹46,000 still fits the FULL cap.
- `DemoSeederIT` (2, Testcontainers):
  - both wallets are FULL, ACTIVE and ledger-backed, with an approved FULL KYC by two different officers and one
    opening entry;
  - through the API, a token whose `sub` is the seeded subject sees FULL KYC and the seeded wallet, and sends
    ₹250 to customer2;
  - a second seed creates nothing and resets nothing.
- `KeycloakRealmIT` (+2): a real Keycloak, importing the real realm file, issues customer1/customer2 tokens whose
  `sub` is the seeded subject. This proves that Keycloak's realm import keeps a user's `"id"`.

### 5. ADRs 0001–0009: exist, match the code, notes added

All nine files exist, and they match SPEC §11's table. Each claim was checked against the code:
- the class names (28 of them, e.g. `TopupLimitChecker`, `FreezeRequestRelay`, `ChainVerifier`, `AesGcmCipher`);
- the constants (the advisory lock key `0x544C_5F41_5544_4954`);
- the configuration (`republish-outstanding-events-on-restart`, `retention: 7d`, Keycloak token lifespans,
  `max-attempts: 3`);
- the versions (Modulith 2.1.2, Keycloak 26.8.2);
- the counts (nine `@Audited` endpoints).

No decision is wrong. Each ADR got a "Code check (2026-11-23, Day 25 B, M16)" section, and the open promises are
now written down:

| ADR | Still open |
| --- | --- |
| 0002 | (addition) fixed customer IDs for the demo seed; `jwk-set-uri` from ADR 0022 |
| 0004 | `lock_timeout` was promised for M13 and not set. Lock waits are still unbounded |
| 0005 | No running resubmission of FAILED publications and no alert on stale ones (promised for M12); a restart republishes the backlog, as Day 25 A saw |
| 0006 | PARKED top-ups have a metric but no alert and no settlement endpoint |
| 0007 | `/aml/alerts` and `/ai-summary` not built (denied); no alert on DLT traffic or stuck freeze requests |
| 0008 | Anchoring `lastHash` outside the DB still out of scope; seeds write no audit rows |
| 0009 | KMS data key and the KYC retention job belong to the AWS half of M14; an erased customer's Keycloak account is still enabled |

0001 and 0003 match with nothing open.

### 6. README rewrite

The README follows the 10 parts of §11:
1. **Pitch.**
2. **Demo:** a video placeholder, and "no live URL yet".
3. **Architecture:** a Mermaid diagram with every module, the outbox, Kafka in both directions, the stores and
   OTLP, plus a module table.
4. **Compliance table:** KYC tiers, PPI limits, high-value review, maker-checker, AML (R1–R4, auto-freeze, STR),
   freeze, audit, DPDP, PCI scope (out of scope by design), webhook integrity. Each row names the rule, how it is
   implemented, and the tests that prove it.
5. **Highlights.**
6. **Stack.**
7. **Run in 3 commands:** `cp .env.example .env`, `docker compose … --profile apps up -d --build`,
   `scripts/smoke.sh`, plus the developer loop with `local,demo`.
8. **Tests:** the counts and the JaCoCo coverage from this branch's `verify`, and the 16 must-pass items of SPEC §9,
   each mapped to its test classes. Every class name was checked to exist.
9. **Performance and security:** the perf numbers stated honestly (the 100/s target not met), and the security
   checklist.
10. **Every ADR, 0001–0022.**

Then comes **Not yet built** (AWS deployment, PDF statements, Envers, AI case summary, plus the smaller gaps). The
older detail sections are kept, shorter, under "More detail": the money path, observability, images, load test,
Keycloak.

Also: `CLAUDE.md` Commands (`,demo`) and the `scripts/smoke.sh` header (the demo profile is what makes the defaults
work).

### 7. Verification status

`./mvnw verify` **passed** on this branch, the whole reactor (BUILD SUCCESS, 7 min):

| Module | Unit tests | Integration tests | Line coverage (JaCoCo) | Branch coverage |
| --- | --- | --- | --- | --- |
| core-app | 1344 (+8: `DemoSeedIdsTest` 6, `HttpCollectionTest` 2) | 816 (+4: `DemoSeederIT` 2, `KeycloakRealmIT` 2) | 96.6% | 89.1% |
| risk-aml-service | 112 (+2: `HttpCollectionTest`) | 62 | 94.1% | 85.1% |
| event-contracts | 36 | | 100% | 100% |

`ModularityTests`, `ArchitectureRulesTest`, `PerfSeederIT` (after the refactor) and `ContainerImageTest` are green.

**Manual checks against the running containers** (Keycloak recreated, images rebuilt, compose profile `apps`):

| Check | Result |
| --- | --- |
| core-app log | profiles `local`, `demo`; "Demo seed: 2 of 2 customer wallets created" |
| `load-tests/keycloak-perf-users.sh` after the Keycloak recreate | 200 users + client added, "user IDs match the seeded subjects" |
| `scripts/smoke.sh` with the **defaults** (customer1 → customer2) | PASSED (Day 25 A: stopped at 409 `WALLET_FROZEN`) |
| `GET /kyc/me` as customer1 | `currentLevel: FULL`, the seeded MIN and FULL cases |
| `GET /wallets/me/statements/2026-11` | 404 (not built) |
| `GET /admin/ledger/accounts/PLATFORM:SUSPENSE/balance` | auditor 404 NOT_FOUND, customer 403 |
| `GET :8081/api/v1/aml/alerts` | 403 |
| `GET /audit/verify` | `OK`, 33,555 events checked |
| History with type, date range, sort; cursor mode; `size=100000` | 200, 200, 400 |
| Webhook without signature | 400 `WEBHOOK_SIGNATURE_INVALID` |
| `HttpCollectionTest` with `audit.http` removed | fails, naming both audit endpoints |

The app containers were stopped afterwards; the infrastructure keeps running. The IntelliJ HTTP Client files were
checked against the API by hand and by the guard test, not run in IntelliJ itself.

### Needs your decision

- **Recreate Keycloak on your other machines/clones.** I recreated the local one (and re-added the 200 perf users with
  `load-tests/keycloak-perf-users.sh`). Anyone else on an old Keycloak container sees the seeded wallets under
  nobody, until `--force-recreate keycloak`.
- **The old customer1/customer2 rows stay in your local database**, under their previous random Keycloak IDs (the
  frozen customer1 wallet included). Nobody can log in as them any more. They are harmless, but `docker compose
  down -v` gives a clean demo database if you want one for the video.
- **perf003–perf200 are still frozen** (Day 25 A). Unchanged by this session.
- **Demo video and `docs/demo-script.md`** (the rest of M16): the `demo` profile and `http/demo.http` are built for
  it. Tell me when you want the script.

### What to learn from it

- **Seed data needs the same identity as the login.** A seeded wallet is useless if the token's `sub` doesn't point
  to it. Fixing the IDs on both sides (realm file and seed), and testing that they agree, is what makes "clone and
  run" work.
- **Extract before you copy.** The second seeder needed the same twenty lines of SQL. Moving them into one class kept
  one place that knows the schema, and the old IT proved the refactor changed nothing.
- **Docs drift; tests don't.** The `http/` collection had silently fallen behind (no audit or history requests, a
  stub comment from M2). A small reflection-based test now makes "every endpoint has a request" part of the build.
- **An ADR is a promise as well as a decision.** Reading them against the code didn't find wrong decisions. It found
  forgotten follow-ups ("M13 should set a lock_timeout"). Writing them down in the ADR keeps them visible.
- **Describe what isn't there.** A README that lists "Not yet built", and a collection that shows the 404/403 an
  unbuilt endpoint gives today, are more convincing to a reviewer than an API table that implies everything works.
- **Be honest with numbers.** The README states that the 100 req/s target is not met and why. A reviewer trusts the
  other numbers more for it.
