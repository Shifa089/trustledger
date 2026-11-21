# Changes

## DAY14 Session B: quality pass (review of core-app against CLAUDE.md and SPEC §6) + first real README

**Goal:** an honest list of every place core-app breaks a rule in `CLAUDE.md` or `docs/SPEC.md` §6
("Cross-cutting conventions"), grouped by severity, **without fixing anything**: you choose what to fix. Plus a
first README that explains how to run the project and what the money path does.

Branch: `feature/day14-quality-pass` (created from `master`), merged into `master`.

**No production code changed.** This session only adds docs.

---

### 1. Review: violations by severity

How the review was done: each rule was checked with a targeted search over `core-app/src/main`, and each hit
was read in context. Checked: money types, `Clock`, `@Transactional` placement and `readOnly`, Lombok, ID
generation, `@Version`, log statements, error handling, DTO/event records, layering and entity exposure,
security rules per controller, remote calls vs. transactions, cross-schema foreign keys, hard-coded limits,
config records. Line numbers are as of commit `63a2c0c`.

**No high-severity violations were found.** The money rules hold everywhere:
- `long` paise in `Money`, `BigDecimal` only for parsing and formatting;
- no `Instant.now()`;
- `@Transactional` only in `application`;
- ordered wallet locks;
- no remote call inside a transaction (`Propagation.NEVER` on `TopupService.create` and the reconciler; S3 upload outside the transaction);
- append-only ledger;
- no cross-schema foreign keys;
- no `@Data` or `@Setter` on entities.

#### Medium

| # | Rule | Where | What is wrong | Possible fix |
|---|---|---|---|---|
| M1 | CLAUDE.md "Never log personal data" | `core-app/src/main/resources/application-local.yml:10` (datasource URL; the same applies to every profile). Logged by Hibernate's `SqlExceptionHelper` (default WARN/ERROR) and again by `shared/web/GlobalExceptionHandler.java:86` and `identity/web/JitProvisioningFilter.java:72` (full exception). | On a constraint violation, PgJDBC includes the server's **`Detail: Failing row contains (...)`**, which holds **every column value of the row**, in the exception message. Encrypted columns are safe, but plaintext free text ends up in the logs: `kyc_cases.rejection_reason` and `wallet_freezes.reason` (the code's own comments say these "may quote personal data"), and `consents.ip`. | Add `logServerErrorDetail=false` to the JDBC URL (all profiles). Optionally, a test that forces a check violation and asserts the log has no row values. |
| M2 | CLAUDE.md Testing rules: "ModularityTests (Modulith verify) **and ArchUnit rules** must stay green" | No ArchUnit test anywhere in `core-app/src/test`. Only `ModularityTests.java` exists. | There are **no ArchUnit rules**. Earlier change logs said "ArchUnit green", which wasn't accurate. Today the code follows the rules (this review checked them by hand), but nothing stops a regression: web → application → domain layering, `@Transactional` only in `application`, no `Instant.now()`, Lombok only on entities, no `double`/`float` in money code. | Add `ArchitectureRulesTest` with those rules. ArchUnit already comes in transitively through Spring Modulith; declaring it directly in test scope would be a small dependency change (ADR + approval). |

#### Low

| # | Rule | Where | What is wrong | Possible fix |
|---|---|---|---|---|
| L1 | SPEC §6 "DTOs and mapping: … MapStruct for mapping"; CLAUDE.md Stack lists MapStruct | Whole codebase. Example: `payments/web/TopupController.java:41` (`TopupResponse.of`). MapStruct isn't in any `pom.xml`. | Mapping is done by hand-written static `of(...)` factories (19 of them), not MapStruct. They're simple and tested, but they break the written convention. | Either add MapStruct, or (recommended) change SPEC §6 and CLAUDE.md to "static factory methods on records" and record that in an ADR. With amounts as strings and masking, the hand-written factories are clearer than MapStruct expressions. |
| L2 | CLAUDE.md "Config via @ConfigurationProperties records; no hard-coded limits" | `kyc/application/KycReviewService.java:42` and `payments/application/HeldTransferService.java:49` (`MAX_PAGE_SIZE = 100`), `payments/application/IdempotencyService.java:33` (`MAX_CLAIM_ATTEMPTS = 3`) | Operational limits are constants. (Field lengths, crypto sizes and the validation bounds inside `*Properties` are format rules, not limits, so they're fine.) | Move the page size into `trustledger.api.max-page-size` (shared) and the claim attempts into `trustledger.payments.idempotency`. |
| L3 | CLAUDE.md "Every endpoint has an explicit role rule" | `shared/web/PingController.java:21` (`GET /ping`) is matched only by the catch-all `shared/security/SecurityConfig.java:129` (`/api/** authenticated`). | No explicit rule or `@PreAuthorize` for `/ping`. It's harmless (it returns only your own subject and roles), but any **new** endpoint someone forgets to list also lands silently in "any logged-in user". | Give `/ping` an explicit rule, and change the catch-all to `denyAll()` so a forgotten endpoint fails closed. `SecurityMatrixIT` would show exactly which paths depend on it. |
| L4 | SPEC §6 "IDs: UUIDs (time-ordered UUIDv7 preferred) generated in the application" | `payments/application/WebhookService.java:75`, `:81`, `:88` (`UUID.randomUUID()`) | `webhook_events.id` values are random v4 UUIDs. Every entity uses `@UuidGenerator(style = VERSION_7)`. v4 keys scatter across the primary-key index of an insert-heavy table. (The random S3 key in `KycDocumentService.java:62` and the correlation ID in `CorrelationIdFilter.java:49` are fine: they aren't row IDs, and being unguessable is a plus.) | A small `Ids.newV7()` helper in `shared` (no new dependency: build the v7 bits from the injected `Clock` + `SecureRandom`). |

#### Informational (deviations from the letter of SPEC §6, not defects)

| # | Topic | Where | Note |
|---|---|---|---|
| I1 | "`@Version` optimistic locking everywhere else" | `wallet/domain/WalletFreeze.java:30` (also `Consent`, `KycDocument`, `LedgerAccount`, `JournalEntry`, `JournalLine`) | The last five are never updated (append-only or immutable), so a version would protect nothing. `WalletFreeze` **is** updated (`end(...)`), but only through its aggregate root `Wallet`, which has `@Version` and changes status in the same unfreeze. Covered in practice; noting it so the reason is written down. |
| I2 | Ownership "`@PreAuthorize("@walletAccess.isOwner(#walletId)")`" | `TopupQueryService.get`, `TransferQueryService.get`, the `/me` endpoints | Ownership is checked in the application services and answered with **404, not 403**, so an attacker can't learn which IDs exist. This is stricter than the SPEC example. Consider updating the SPEC wording. |
| I3 | "Profiles: `local`, `test`, `aws`" | `core-app/src/main/resources/` | Only `local` exists. Tests use `src/test/resources/config/application.yml`; `aws` comes with M14. |
| I4 | Events to Kafka via `@Externalized`, Kafka conventions | — | Not built yet (M8). `TopupCaptured`, `TransferCompleted`, `WalletFrozen` are records, already published in-process. |

Outside this review's scope (SPEC §5, noted only): `GET /admin/ledger/accounts/{code}/balance` has a security
rule (`SecurityConfig.java:114`) but no controller yet.

---

### 2. README

| File | What |
|---|---|
| `README.md` | The top part is rewritten, following SPEC §11:<br>- the pitch and a status line (M0–M7 done, what's next);<br>- **the money path**: wallets and KYC tiers, the double-entry ledger, P2P transfers (idempotency, ordered locking, limits, high-value hold, BOLA), top-ups (order → checkout → signed webhook → settlement / suspense → reconciler);<br>- a Mermaid architecture diagram and the module rules;<br>- **run locally** (the same 4 commands, a PowerShell note, a URL table);<br>- "try the money path" (the `http/` files in order, plus the e2e doc);<br>- tests with examples;<br>- tech stack;<br>- an ADR table (only ADRs that exist).<br>The "Local authentication" section (clients, test users, OTP, calling the API) is kept word for word. |

Two claims were checked against the code before writing:
- "ArchUnit" was removed from the README, because there are no ArchUnit rules (M2).
- ADRs 0005 and 0007–0009 aren't listed, because they aren't written yet.

---

### 3. How to verify Day 14 Session B

| # | Command | Why |
|---|---|---|
| 1 | `./mvnw clean verify` | Still green (no code changed). |
| 2 | Open `README.md` on GitHub after the push | The Mermaid diagram renders and the links resolve. |
| 3 | Re-run any finding's search, e.g. `grep -rn "UUID.randomUUID" core-app/src/main` | Each finding can be reproduced from its file and line. |

### 4. Machine setup

Nothing new.

### 5. Deferred: your choice

Every finding above is **unfixed on purpose**. Suggested order if you want all of them:
1. M1 (one URL parameter);
2. M2 (the rules guard everything else);
3. L3;
4. L2;
5. L4;
6. L1 (a decision plus SPEC/ADR wording, not code).

### 6. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS** (3 min 30 s): core-app **993 unit / 590 integration** tests,
risk-aml-service green, `ModularityTests` green. No production or test code changed.

---

### What to learn from this

- **A rule nobody checks is a wish.** CLAUDE.md said "ArchUnit rules must stay green", and three change logs
  repeated it, but the rules never existed. Reviews should check that the guard exists, not only that the
  code follows the rule today.
- **Data leaks through side doors.** The code never logs personal data on purpose. But the database driver
  writes whole rows into exception messages, and two layers then log those exceptions. Logging policy has to
  cover framework and driver output too.
- **Fail closed by default.** A catch-all `authenticated()` is convenient until someone forgets to list a new
  admin endpoint. `denyAll()` turns that mistake into a visible 403 in tests instead of a silent hole.
- **Sometimes the spec should change, not the code.** The 404 ownership checks and the hand-written factories
  are deliberate and arguably better than what the SPEC says. Then the fix is to update the document (and an
  ADR), not to make the code match it.
