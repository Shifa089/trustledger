# Changes

## DAY6: Milestone M4: wallet module

**Goal:** the `wallet` module (FR-W-1 to FR-W-3). The parts:
- **Tables.** `wallet.wallets` and `wallet.wallet_freezes`, with the §4 CHECK constraints and more.
- **Aggregate.** `Wallet` with `debit`, `credit`, `hold`, `releaseHold`, `freeze(reason, source, by)` and `unfreeze`, enforcing the invariants.
- **Creation.** An `@ApplicationModuleListener` on `KycLevelUpgraded`: MIN opens a wallet, FULL upgrades the tier. Idempotent. Publishes `WalletCreated(walletId, userId)`, `WalletFrozen` and `WalletUnfrozen`.
- **Limits.** `LimitsProperties` (`trustledger.limits.*`), a validated `@ConfigurationProperties` record holding the limits table.
- **`WalletApi`** for payments: `findByUser`, `lockForUpdate(List<UUID>)` (PESSIMISTIC_WRITE, ascending ID order), `debit`, `credit`, `hold`, `releaseHold`, `limitsFor`.
- **Endpoints.** `GET /api/v1/wallets/me` (CUSTOMER); `POST /api/v1/compliance/wallets/{id}/freeze` and `/unfreeze` (COMPLIANCE_OFFICER, reason required).

Branch: `feature/m4-wallet` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **No event outbox yet.** The Modulith event publication registry (`event_publication`) stays in M8. Until then, an event whose listener fails is not retried. A FULL event opens a wallet directly, which covers a lost MIN event.
2. **New `unfreeze_reason` column.** SPEC §4 only had `reason`, so it is updated.
3. **"Limits used" in `/wallets/me` comes later (M6/M7).** The usage numbers need transfers and top-ups. Payments will supply them through a small SPI, because wallet must not depend on payments.
4. **`ledger_account_id`** is a nullable column with a TODO for M5, not an empty port.

---

### 1. How to verify Day 6

Run these from `dev/`. Start **Docker Desktop** first.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build: 543 core-app unit tests, 318 core-app ITs (PostgreSQL, Redis, Keycloak and LocalStack in Testcontainers), AML IT, Modulith check, JaCoCo. Same as CI. |
| 2 | `./mvnw -pl core-app verify -DskipUnitTests -Dit.test='Wallet*IT,SecurityMatrixIT'` | Just the wallet ITs and the security matrix. |
| 3 | `./mvnw install -DskipTests` (needed once: core-app has a new dependency) | So `-pl core-app` runs resolve `spring-modulith-events-api`. |
| 4 | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak localstack`, then `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local` | Flyway applies **V5** and **V6**. |
| 5 | Run `http/auth.http` (tokens, then customer1's minimum KYC), then `http/wallet.http` top to bottom | `/wallets/me` shows the MIN wallet. officer1 freezes it, and the customer sees FROZEN. A second freeze gives 409. The customer trying to freeze gets 403. officer2 unfreezes. An unknown wallet gives 404. |
| 6 | Finish `http/kyc.http` (officer2 approves full KYC), then `GET /wallets/me` again | Same `walletId`, but `tier` is now FULL and `dailyTransfer` is `"100000.00"`. |
| 7 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "select source, frozen_by, frozen_at, unfrozen_by, unfrozen_at from wallet.wallet_freezes"` | The freeze history, with one closed row. |

**Results:** `./mvnw clean verify` ended in **BUILD SUCCESS**.
- **Unit tests:** core-app 543, up from 464 (+79). New: `WalletTest` 35, `LimitsPropertiesTest` 12, `WalletApiImplTest` 9, `WalletProvisioningServiceTest` 6, `MoneyPropertyConverterTest` 6, `WalletComplianceServiceTest` 4, `WalletFreezeTest` 4, `KycLevelUpgradedListenerTest` 3.
- **Integration tests:** core-app 318, up from 262 (+56). New: `WalletMigrationIT` 13, `WalletControllerIT` 13, `WalletApiIT` 11, `WalletCreationScenarioIT` 5, `SecurityMatrixIT` +14 (107 → 121). The AML IT passed (1/1), and `ModularityTests` stayed green.
- **Line coverage (JaCoCo):** `wallet.domain` 100%, `wallet.application` 100%, `wallet.infrastructure` 100%, `wallet` (API records) 97.5%, `wallet.web` 94.4%, `shared.config` 100%.
- **Log check:** the build log contains none of the test freeze reasons. Reasons are free text and are never logged.

I did not run the live check against the compose stack (steps 4 to 7) in this session.

---

### 2. What changed and why

#### Database

| Object | Change | Why |
|---|---|---|
| `V5__wallet_create_wallets.sql` | `wallet.wallets`: `id`, `user_id`, `tier`, `status`, `balance_minor`, `held_minor`, `currency`, `ledger_account_id` (nullable, TODO M5), `created_at`, `updated_at`, `version` | SPEC §4. Money is `bigint` paise. |
| `uk_wallets_user` | Unique `user_id` | One wallet per user. This is also what makes creation idempotent when two events race. |
| `ck_wallets_balance_non_negative`, `ck_wallets_held_non_negative`, `ck_wallets_held_within_balance` | `balance >= 0`, `held >= 0`, `held <= balance` | The §4 invariants, a second time in the database: the last line of defence against a bug or a manual `UPDATE`. |
| `ck_wallets_tier`, `ck_wallets_status`, `ck_wallets_currency` | Only MIN/FULL, ACTIVE/FROZEN/CLOSED, INR | Same lists as the code. |
| `V6__wallet_create_wallet_freezes.sql` | `wallet.wallet_freezes`: `source`, `reason`, `frozen_by/at`, `unfrozen_by/at`, **`unfreeze_reason`**, `created_at` | Freeze history for audits (FR-W-3). An unfreeze needs a reason as much as a freeze. |
| `fk_wallet_freezes_wallet` | FK to `wallet.wallets` | Allowed: same schema. |
| `uk_wallet_freezes_one_open` | Partial unique index `(wallet_id) WHERE unfrozen_at IS NULL` | At most one open freeze per wallet, even for raw SQL. |
| `ck_wallet_freezes_officer`, `_reason`, `_unfreeze_complete`, `_unfreeze_reason`, `_unfreeze_after_freeze` | A COMPLIANCE freeze names the officer. Reasons are not blank. An unfreeze sets who, when and why together. An unfreeze is not before the freeze. | No half-written history row can exist. |

#### Wallet module

| File | Change | Why |
|---|---|---|
| Base package: `WalletApi`, `WalletView`, `WalletLimits`, `WalletTier`, `WalletStatus`, `FreezeSource`, `WalletCreated`, `WalletFrozen`, `WalletUnfrozen` | The public API, as records and enums | Modulith: other modules see only these types. The events carry IDs only; the free-text reason is left out because it may quote personal data. |
| `domain/Wallet` | `open`, `upgradeTo` (never downgrades), `debit`, `credit`, `hold`, `releaseHold`, `freeze`, `unfreeze`, `available()`, `currentFreeze()`, `freezeHistory()` (read-only) | The rules are listed below the table. |
| `domain/WalletFreeze` | History entity, part of the `Wallet` aggregate (`@OneToMany(mappedBy)`, cascade). `end()` only once, and never before the start. | "One open freeze, and only while FROZEN" is checked in one place. |
| `domain/WalletRepository` + `infrastructure/JpaWalletRepository` | `lockAllOrderedById` (`@Lock(PESSIMISTIC_WRITE)` with `... where id in :ids order by w.id`), `lockById`, `lockByUserId`, `findByUserId`, `saveAndFlush` | The lock order comes from **PostgreSQL's** `ORDER BY`, not from sorting in Java (see section 5). |
| `domain/WalletLocks` + `infrastructure/JpaWalletLocks` | `EntityManager.getLockMode(wallet) == PESSIMISTIC_WRITE` | Lets `WalletApi` refuse to move money on a wallet the caller forgot to lock. |
| `application/WalletApiImpl` | `lockForUpdate` removes duplicate IDs and gives `NOT_FOUND` if any wallet is missing. Mutations are `@Transactional(propagation = MANDATORY)` and refuse an unlocked wallet with `IllegalStateException`. `limitsFor` reads `LimitsProperties`. | Payments owns the transaction: wallet change, ledger posting and transfer commit together. A missing lock is a bug, so it fails loudly. |
| `application/KycLevelUpgradedListener` | `@ApplicationModuleListener` on `KycLevelUpgraded`. On `DataIntegrityViolationException` (a lost insert race) it applies the event once more. | It runs after the KYC commit in its own transaction, so a wallet problem never undoes a KYC approval. |
| `application/WalletProvisioningService` | MIN with no wallet: open MIN and publish `WalletCreated`. FULL: upgrade, or open FULL if no wallet exists. The same level twice does nothing. Runs in its own `REQUIRES_NEW` transaction, with `lockByUserId` first. | Idempotent. A retry after a lost race finds the winner's wallet. A tier upgrade waits for a running transfer. |
| `application/LimitsProperties` | `trustledger.limits.{high-value-threshold, min.*, full.*}` (startup checks below the table). `forTier()` returns `WalletLimits`. | No hard-coded limits, and no unsafe configuration either (same idea as `KycProperties`). |
| `application/WalletComplianceService` | `freeze` and `unfreeze`: lock the row, call the domain method, `saveAndFlush`, **then** publish | If the database refuses, no event goes out. A freeze waits for a running transfer. |
| `application/WalletQueryService`, `MyWalletView`, `ComplianceWalletView` | Amounts as decimal strings. `/me` has no `userId`. `CurrentFreeze.toString()` masks the reason. | SPEC §6 money format. Free text stays out of logs. |
| `web/WalletController` | `GET /wallets/me`: the wallet comes from the token. Without one, 403 `KYC_REQUIRED`. | No ID in the path, so there is no BOLA surface. |
| `web/ComplianceWalletController` | `POST /compliance/wallets/{id}/freeze` and `/unfreeze` with `@NotBlank @Size(max=500) reason`. The officer comes from the token. | FR-W-3. |
| `web/Callers` | Token subject → user ID (bean name `walletCallers`) | Same helper as kyc; each module keeps its web helpers internal. |

The `Wallet` rules:
- An amount must be more than 0 and in INR (otherwise `VALIDATION_FAILED`).
- `debit` and `hold` need enough *available* balance (otherwise `INSUFFICIENT_FUNDS`).
- A FROZEN wallet refuses `debit`, `credit` and `hold` with `WALLET_FROZEN`. `releaseHold` still works.
- A CLOSED wallet refuses everything with `INVALID_STATE_TRANSITION`.
- Freezing a frozen wallet, or unfreezing an active one, gives `INVALID_STATE_TRANSITION`.
- An officer can't unfreeze their own wallet (`MAKER_CHECKER_VIOLATION`).
- Overflow throws `ArithmeticException` and never wraps around.

The `LimitsProperties` startup checks fail the application on:
- a missing or zero amount
- MIN with `p2p-allowed: true`
- a P2P tier without `daily-transfer`, or a `daily-transfer` without P2P
- MIN limits higher than FULL limits
- a threshold above `daily-transfer`

#### Shared and configuration

| File | Change | Why |
|---|---|---|
| `shared/config/MoneyPropertyConverter` | `@ConfigurationPropertiesBinding` `String → Money` through `Money.parse` | Configuration amounts are `"10000.00"` strings in rupees. They are never floats and never rounded; `10000.005` fails startup. |
| `SecurityConfig` | `/api/*/wallets/**` → `hasRole(CUSTOMER)`. Compliance wallet endpoints are covered by the existing `/compliance/**` rule. | Every endpoint has an explicit role rule. A wrong role gets 403 before the body is parsed. |
| `application.yml` | `trustledger.limits.*` with the SPEC §2 values (quoted) | FR-W-2. |
| `core-app/pom.xml` | `spring-modulith-events-api` (version from the Modulith BOM) | This jar holds `@ApplicationModuleListener`, and it was not on the classpath. It is part of Spring Modulith, which is already in the stack, and contains annotations only. |
| `docs/SPEC.md` §4 | The `wallet_freezes` row: `unfreeze_reason`, one open freeze, the FK | Docs match the code. |
| `http/wallet.http` | `/me`, freeze, the customer getting 403, unfreeze, an unknown wallet | Manual smoke test. |

#### Tests

| File | What it proves |
|---|---|
| `WalletTest` (35) | Open, tier upgrade with no downgrade. Each movement's happy path. Debiting the whole available balance is allowed; one paisa more is `INSUFFICIENT_FUNDS` and changes nothing. Held money can't be spent. A zero amount or USD is refused. Overflow never wraps. Frozen: no debit, credit or hold, but `releaseHold` works. Closed: everything refused. Freeze records who, why and when (with the reason stripped). AML freezes need no officer; COMPLIANCE freezes do. Double freeze → 409. Blank, null or 501-character reason → 400. Unfreeze closes the same row. Unfreezing your own wallet → 409. The history is read-only. |
| `WalletFreezeTest` (4) | Starts open. Ends only once. Can't end before it started. Required fields. |
| `LimitsPropertiesTest` (12) | The spec table is valid and maps to paise. Each unsafe combination fails with the property name in the message. **Binding through a real context**: decimal strings bind; `10000.005` and MIN P2P fail startup. |
| `WalletProvisioningServiceTest` (6), `KycLevelUpgradedListenerTest` (3) | MIN opens a wallet and publishes `WalletCreated`. FULL opens FULL or upgrades. Repeats and late MIN events do nothing. A lost race publishes nothing and is retried once; a second failure is not swallowed. |
| `WalletApiImplTest` (9), `WalletComplianceServiceTest` (4), `MoneyPropertyConverterTest` (6) | Duplicates are removed and the database order is returned. An unknown ID → 404. Empty or null IDs are rejected. **Unlocked wallet → `IllegalStateException` and no change.** Freeze and unfreeze publish their events; a refused freeze publishes nothing. |
| `WalletMigrationIT` (13, raw SQL) | The database refuses a negative balance, a negative hold, held > balance (on insert **and** on a later `UPDATE`), a second wallet per user, an unknown tier, status or currency, a freeze of an unknown wallet, a second open freeze (closed history rows are fine), a COMPLIANCE freeze without an officer, a blank reason, an unknown source, a half-written unfreeze, and an unfreeze before the freeze. |
| `WalletCreationScenarioIT` (5) | **Real minimum KYC → `WalletCreated`** (Modulith `Scenario`) and an empty ACTIVE MIN wallet. The same event twice → one row and one `WalletCreated`. **Real full-KYC approval → tier FULL with the same wallet ID.** A late MIN event doesn't downgrade. An event whose transaction rolls back creates no wallet. |
| `WalletApiIT` (11) | **Lock order is PostgreSQL's:** for `1000…` and `8000…` UUIDs, Java sorts `8000…` first while `lockForUpdate` returns `1000…` first. Calling without a transaction → `IllegalTransactionStateException`. Without the lock → refused, nothing written. A wallet read earlier in the same transaction can still be locked. Every movement is persisted. A refused debit rolls back the credit in the same transaction. Frozen-wallet refusals in the database. Limits per tier. **50 concurrent credits and debits on one wallet → exact final balance (no lost update). 100 opposite transfers `a→b` / `b→a` at once → no deadlock, and `a + b` unchanged.** |
| `WalletControllerIT` (13) | `/me`: 403 `KYC_REQUIRED` before KYC. MIN values (`"1500.50"`, held, available, null P2P limits, no `userId`). FULL limits. Each customer sees only their own wallet. An officer → 403. Freeze: 200, a DB row, `WalletFrozen`, and the customer sees FROZEN. Twice → 409. Unfreeze: 200, the row closed with who and why, `WalletUnfrozen`. Unfreezing an active wallet → 409. Officer unfreezing their own wallet → 409 and it stays FROZEN. Missing, blank or long reason → 400 and nothing changes. Unknown wallet → 404. Customer → 403. |
| `SecurityMatrixIT` (+14) | `/wallets/me` and both compliance wallet endpoints across NONE, NO_ROLES, CUSTOMER, OFFICER, ADMIN and AUDITOR. |

**Fixes found while testing**

- **`List.of(...).contains(null)` throws a `NullPointerException`.** My "no null IDs" guard in `lockForUpdate` crashed on every immutable list, so the happy path failed too. The unit test caught it on the first run. **Fix:** `stream().anyMatch(Objects::isNull)`.
- **`@ApplicationModuleListener` was not on the classpath.** `spring-modulith-starter-core` brings `api`, `core`, `apt` and `moments`, but not `events-api`. I added it (annotations only). Without the events runtime there is no `@EnableAsync`, so the listener runs **synchronously after commit**, in its own transaction. The ITs are deterministic because of this. It changes once M8 adds the registry.

---

### 3. Machine setup done on Day 6 (not in Git)

- **Branch** `feature/m4-wallet` created from `master`, committed and merged back into `master` (`--no-ff`). Not pushed.
- Maven downloaded `spring-modulith-events-api:2.1.2`. Run `./mvnw install -DskipTests` once before `-pl` runs.
- No `.env` changes, and no new Docker images.

### 4. Deferred

- **Event publication registry (outbox)**: M8. Until then a failing wallet listener is logged and not retried.
- **"Limits used"** (sent today, added this month) in `/wallets/me`: M6/M7, through an SPI that payments implements.
- **`ledger_account_id`**: M5. The ledger listens to `WalletCreated` and creates `WALLET:<id>`.
- **AML freezes** (`AccountFreezeRequested` → `FreezeSource.AML`): M9. The aggregate already supports a freeze without an officer.
- **Audit events** for freeze and unfreeze: M10. For now an INFO log line records the wallet and officer IDs, never the reason.
- **Wallet-frozen e-mail** (FR-N-1): the notification module listens to `WalletFrozen`.
- **Balance cap** is not enforced by `credit`: payments checks it with `limitsFor` (M6). A reversal must always be able to pay back.
- **RBI PPI values** in the limits table still need checking against the current directions before any real use.

---

### 5. What to learn from Day 6

1. **Lock in one global order, or deadlock.** Transfer A locks `a` then `b`; transfer B locks `b` then `a`; each waits for the other forever. If every transaction locks in the same order, no cycle can form. The `oppositeTransfersNeverDeadlock` test runs 100 such transfers at once to prove it.
2. **"Ascending" depends on who sorts.** Java's `UUID.compareTo` compares the high 64 bits as a *signed* long, so `8000…` comes before `1000…`. PostgreSQL compares bytes *unsigned* and says the opposite. Sorting in Java while locking in SQL would quietly break the rule for some ID pairs. So the database's `ORDER BY id ... FOR UPDATE` decides the order, and a test pins the difference down.
3. **Make the safe path the only path.** `WalletApi` mutations are `MANDATORY` (no transaction → fail) and check the row lock (no lock → fail). A payments developer who forgets a step gets an exception in their first test, not a lost update in production.
4. **Idempotent consumers: let a constraint decide.** "Check, then insert" has a gap where two events both see "no wallet". The unique `user_id` closes it: one insert wins, the other gets an exception, and a retry in a fresh transaction finds the winner's wallet. The same applies to Kafka consumers in M9.
5. **After-commit listeners protect the publisher.** The wallet listener runs only once KYC has committed, and in its own transaction. A KYC approval that rolls back creates no wallet, and a wallet failure can't undo a KYC approval. The cost is a gap where an event can be lost, which is exactly what the M8 outbox closes.
6. **Validate configuration like user input.** Limits are compliance rules. A typo like `p2p-allowed: true` on MIN, or a threshold above the daily limit, should stop the application at startup. Binding money as decimal strings through `Money.parse` keeps floats out even in YAML.
7. **Check the database for the invariants that matter most.** `held <= balance` lives in the aggregate *and* in a CHECK constraint. The migration IT proves that even a raw `UPDATE` can't break it.
