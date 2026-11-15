# Changes

## DAY5 Session A: Milestone M3 (part 1): kyc module without documents

**Goal:** the `kyc` module's core (FR-KYC-1, FR-KYC-3, FR-KYC-4). The parts:
- **Table.** `kyc.kyc_cases`, with maker-checker enforced by a database CHECK constraint.
- **`KycCase` aggregate.** Explicit transitions: `submitMin`, `autoApproveMin`, `submitFull`, `verify`, `approve`, `reject`. An illegal transition throws.
- **`POST /api/v1/kyc/min`.** Takes a valid PAN, an Indian mobile number and a date of birth giving age 18 or over (Asia/Kolkata). The case is approved automatically, and the verified mobile is stored on the user.
- **`GET /api/v1/kyc/me`.** The caller's own KYC status.
- **Public event.** `KycLevelUpgraded(userId, level)` in the kyc base package.

Documents, S3/LocalStack, `POST /kyc/full` and the compliance endpoints come in Session B.

Branch: `feature/m3-kyc-min` (created from `master` after PR #4), merged into `master`.

---

### 1. How to verify Day 5 Session A

Run these from `dev/`. Start **Docker Desktop** first.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build: 387 core-app unit/slice tests, 187 core-app ITs (real PostgreSQL, Redis and Keycloak in Testcontainers), AML IT, Modulith check, JaCoCo. Same as CI. |
| 2 | `./mvnw -pl core-app verify -DskipUnitTests -Dit.test='MinKycIT,KycMigrationIT,KycLevelUpgradedScenarioIT'` | Just the new kyc ITs. |
| 3 | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak` | Local stack for the live check. |
| 4 | `./mvnw install -DskipTests` then `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local` | Starts core-app; Flyway applies **V3** (log: `now at version v3`). |
| 5 | Run the new KYC requests at the end of `http/auth.http` (customer1) | `POST /kyc/min` → 201 APPROVED with `XXXXX1234X`; again → 409; `GET /kyc/me` → `currentLevel: MIN`; bad PAN → 400. |
| 6 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "select level, status, pan_enc, dob_enc from kyc.kyc_cases"` | Only ciphertext, never the PAN or date of birth. |
| 7 | Same psql: `update kyc.kyc_cases set verified_by = gen_random_uuid(), approved_by = verified_by;` | Fails on `ck_kyc_cases_maker_checker`: the database itself refuses one officer in both roles. |

**Results:** `./mvnw clean verify` ended in BUILD SUCCESS on the second run. The first run failed on one wrong expectation in a security-matrix row (see Fixes).
- **Unit tests:** core-app 387 (new: `KycCaseTest` 31, `MinKycServiceTest` 8, `AgePolicyTest` 5, `KycPropertiesTest` 4, `EncryptedLocalDateConverterTest` 3; `ErrorCodeTest` +4 rows).
- **Integration tests:** core-app 187 (new: `MinKycIT` 19, `KycMigrationIT` 9, `KycLevelUpgradedScenarioIT` 2; `IdentityApiIT` 7 → 10; `SecurityMatrixIT` 70 → 79). The AML IT passed (1/1) and ModularityTests stayed green.
- **Line coverage:** `kyc` 100%, `kyc.application` 100%, `kyc.domain` 100%, `kyc.web` 84.6%, `identity.application` 98.1%.
- **Log check:** none of the test PANs, names or dates of birth appear in the build log.

I did not run the live check against the compose stack (steps 3 to 7) in this session.

---

### 2. What changed and why

#### Database (`V3__kyc_create_kyc_cases.sql`)

| Object | Change | Why |
|---|---|---|
| `kyc.kyc_cases` | `id`, `user_id` (no FK: other schema), `level` (MIN/FULL), `status` (SUBMITTED/VERIFIED/APPROVED/REJECTED), `full_name_enc`, `pan_enc` + `pan_hash`, `dob_enc`, `address_enc`, `verified_by/_at`, `approved_by/_at`, `rejected_by/_at`, `rejection_reason`, `created_at`, `updated_at`, `version` | SPEC §4, extended as agreed in the plan: the name as on the PAN card is collected by FR-KYC-1 but had no column. `pan_hash` (HMAC blind index) lets a PAN be found without storing it in clear, so a later "one PAN, one account" rule is a unique index away. The `_at` and `rejected_by` columns record who did what, and when. |
| `ck_kyc_cases_maker_checker` | `approved_by IS NULL OR approved_by <> verified_by` | FR-KYC-3 in the database too. It catches a buggy service and a manual UPDATE alike. |
| `ck_kyc_cases_full_four_eyes` | A FULL case can only be APPROVED with both officer IDs set | The maker-checker check alone would pass with `verified_by` NULL, because `x <> NULL` is NULL and a CHECK accepts NULL. This closes that gap. MIN cases are system-approved and exempt. |
| `ck_kyc_cases_rejection_reason` | `(status = 'REJECTED') = (rejection_reason IS NOT NULL)` | "Reject with a reason" is enforced by the database, not just the API. |
| `uk_kyc_cases_user_level_live` | Partial unique index `(user_id, level) WHERE status <> 'REJECTED'` | At most one live case per level. It is also the concurrency guard for `POST /kyc/min` (see section 5). A rejected FULL case can be followed by a new one. |
| Other | Level/status value checks; `pan_enc`/`pan_hash` pair check; indexes on `(user_id, created_at)` and `pan_hash` | Same pattern as `identity.users`. |

#### KYC module (`com.trustledger.core.kyc`)

| File | Change | Why |
|---|---|---|
| `KycLevel`, `KycLevelUpgraded`, `package-info` (base package = public API) | `enum KycLevel {MIN, FULL}`; `record KycLevelUpgraded(UUID userId, KycLevel level)` with null checks | FR-KYC-4. Wallet (M4) will listen to it. IDs only, no personal data. |
| `domain/KycCase` | Entity (`@Getter`, protected no-args, UUIDv7, `@Version`, encrypted fields). Transitions: `submitMin`, `autoApproveMin`, `submitFull`, `verify`, `approve`, `reject`. Illegal moves throw `INVALID_STATE_TRANSITION` (409). The same officer approving throws `MAKER_CHECKER_VIOLATION` (409), as does an officer reviewing their own case. A blank or overlong reason gives `VALIDATION_FAILED`. | The state machine lives in one class and is fully unit-tested. MIN never goes through officer steps; FULL can never be auto-approved. |
| `domain/KycStatus` | The four states and `isFinal()`; Javadoc shows the transition diagram | One place to read the allowed moves. |
| `domain/AgePolicy` | `isAtLeast(years, dob, today)`, a pure function | Easy to test at the exact edge (birthday today, one day short, 29 February). |
| `domain/KycCaseRepository` + `infrastructure/JpaKycCaseRepository` | Port and Spring Data implementation | Same "infrastructure implements ports" pattern as identity. |
| `application/MinKycService` | `submit(MinKycCommand)` in **one transaction**: (1) reject if a MIN case already exists, (2) date of birth not in the future, (3) 18 or over in **Asia/Kolkata** via the injected `Clock`, (4) insert the auto-approved case with `saveAndFlush`, (5) `IdentityApi.recordVerifiedPhone`, (6) publish `KycLevelUpgraded` | The order is deliberate (see section 5). Any failure rolls back both the case and the phone, and no event is published. |
| `application/KycQueryService`, `KycOverview`, `KycCaseView` | `overview(userId)`: the highest approved level (`NONE`/`MIN`/`FULL`) and every case, with the PAN masked | Plaintext never leaves the application layer. |
| `application/KycProperties` (+ `KycConfig`) | `trustledger.kyc.min-age-years: 18`; startup fails outside 18-100 | CLAUDE.md: no hard-coded limits. Configuring an age below majority would be a compliance bug, so the app refuses to start. |
| `application/MinKycCommand` | `toString()` masks name, PAN, mobile and date of birth | It cannot leak personal data if logged by accident. |
| `web/KycController` | `POST /api/v1/kyc/min` (201) and `GET /api/v1/kyc/me`, `@PreAuthorize("hasRole('CUSTOMER')")`. The request record uses `@NotBlank @Size`, `@NotNull @Past`, `@ValidIndianMobile` and `@ValidPan`, with a masked `toString()`. The user ID comes from the token through `IdentityApi.findUserId`. | No user ID in the URL or body, so no BOLA. kyc only uses identity's public API, so ModularityTests stays green. |

#### Identity and shared changes

| File | Change | Why |
|---|---|---|
| `IdentityApi.recordVerifiedPhone(userId, phone)` + `IdentityApiImpl` | Normalises the number, looks it up by blind index, and returns `PHONE_ALREADY_REGISTERED` if another user owns it. The same number again is a no-op. Otherwise it calls `User.changePhone` and `saveAndFlush`; a unique violation from a concurrent registration also maps to 409. It joins the caller's transaction. | The Day 4 deferred item: KYC is where the mobile number gets verified. This was decision 2 in the plan: an in-process call in the same transaction, not an event, so a phone conflict rolls the KYC case back. |
| `shared/crypto/EncryptedLocalDateConverter` | AES-GCM over the ISO date | The entity keeps a real `LocalDate` for the date of birth, and the database holds only ciphertext. |
| `ErrorCode` | `INVALID_STATE_TRANSITION` (409), `KYC_ALREADY_SUBMITTED` (409), `PHONE_ALREADY_REGISTERED` (409), `AGE_REQUIREMENT_NOT_MET` (422) | Stable codes for clients. |
| `SecurityConfig` | URL rule `/api/*/kyc/**` → CUSTOMER | As on Day 4: authorization runs before the body is parsed, so staff get 403, not 400. |
| `application.yml` | `trustledger.kyc.min-age-years: 18` | |
| `docs/SPEC.md` §4 | `kyc_cases` row updated with the new columns and constraints | The spec matches the schema (decision 1 in the plan). |
| `http/auth.http` | customer1: min KYC, `/kyc/me`, invalid PAN | Manual smoke test. |

#### Tests

| File | What it proves |
|---|---|
| `KycCaseTest` (31) | Every legal transition, with the officer and time recorded. Every illegal one throws `INVALID_STATE_TRANSITION`: approve before verify, verify twice, officer steps on MIN, auto-approve on FULL, anything after APPROVED or REJECTED. The same officer verifying and approving gives `MAKER_CHECKER_VIOLATION`, and the state is unchanged. An officer cannot review their own case. A reason is required for rejection. Invalid PANs, names and addresses are rejected. |
| `AgePolicyTest` (5) | 18th birthday today passes; one day short fails; 29 February birthdays; bad arguments. |
| `MinKycServiceTest` (8, mocks) | Happy path stores, records the phone and publishes the event. **01:30 in India while it is still the day before in UTC counts as the birthday.** If the user is too young, nothing is written or published. A lost insert race becomes `KYC_ALREADY_SUBMITTED` and publishes nothing. `MinKycCommand.toString()` is masked. |
| `KycPropertiesTest` (4), `EncryptedLocalDateConverterTest` (3), `ErrorCodeTest` (+4) | Startup validation, date encryption round trip, and the new codes' statuses. |
| `KycMigrationIT` (9, raw SQL) | The database rejects the same officer in both roles, both on INSERT and on a later UPDATE. It rejects APPROVED FULL without both officers, REJECTED without a reason or a reason without REJECTED, unknown level or status, and a second live case per level, while a new case after a rejected one is allowed. The PAN ciphertext and its blind index come together. |
| `MinKycIT` (19) | Happy path: 201 APPROVED, masked PAN, no plaintext in the response or in any column, `verified_by`/`approved_by` NULL, the phone findable through `IdentityApi`. `/kyc/me` returns NONE before and MIN after, and users only see their own cases. Rejections: invalid PAN ×4 and invalid mobile ×4 give 400 with the field name and store nothing; missing fields give 4 violations; a future date of birth gives 400; under 18 gives 422 and stores neither a case nor a phone; exactly 18 today passes. A repeat gives 409 with still one case. Another user's phone gives 409 **and the inserted case is rolled back**. **8 concurrent submissions from one user give exactly one 201, seven 409s and one row.** No token gives 401; staff get 403 even with an invalid body. |
| `KycLevelUpgradedScenarioIT` (2) | Spring Modulith `Scenario`: `stimulate(submit).andWaitForEventOfType(KycLevelUpgraded).matching(userId).toArriveAndVerify(level == MIN)`. A rejected submission publishes nothing (`@RecordApplicationEvents`). It runs in the shared context through `@EnableScenarios`, so no extra containers start. |
| `IdentityApiIT` (+3) | `recordVerifiedPhone`: stores the normalised number and is idempotent; another user's number gives 409 and leaves the user without a phone; an unknown user gives 404; a malformed number gives 400. |
| `SecurityMatrixIT` (+9) | `/kyc/min` and `/kyc/me` across roles. |

**Fixes found while testing**

- **Concurrent `POST /kyc/min` would have ended in a 500.** In the first draft the phone was written
  before the case. Two simultaneous requests from the same user both passed the "already submitted?"
  check, then both updated the same `users` row, and the loser hit an optimistic-lock failure that maps
  to 500. **Fix:** insert the case first. The partial unique index makes the second insert wait for the first
  transaction, then fail with a unique violation, which becomes a clean 409 before the users row is
  touched. A phone conflict after the insert still rolls the case back, because it is all one transaction.
  `MinKycIT.concurrentSubmissionsOfOneUserCreateExactlyOneCase` guards this.
- **Security matrix expectation:** a `POST` with no body at all gives 400 ("required request body is
  missing"), not 415. Spring checks for the body before the content type. The row's purpose is "CUSTOMER
  gets past security", so the expectation was corrected to 400.
- **Ambiguous `assertThat`** on the result of `TransactionTemplate.execute(...)`: the compiler could not choose
  between the `Predicate` and `IntPredicate` overloads. Fixed by assigning to a typed local first.

---

### 3. Machine setup done on Day 5 Session A (not in Git)

- **Branch** `feature/m3-kyc-min` created from `master`, committed and merged back into `master` (`--no-ff`).
- No `.env` changes: the new setting has a default in `application.yml`.
- The local database moves to V3 on the next `spring-boot:run`. No reset needed.

### 4. Deferred to Session B and later

- **Session B (M3 part 2):**
  - `kyc.kyc_documents`, the S3 port with an AWS SDK adapter on LocalStack, magic-byte and 5 MB checks, SHA-256 and presigned URLs.
  - `POST /kyc/full` and `POST /kyc/cases/{id}/documents`, with an owner check against BOLA.
  - The compliance queue plus verify, approve and reject endpoints. The aggregate is ready for them.
- **PAN uniqueness across accounts:** `pan_hash` is indexed but not unique. Making it unique (where not
  rejected) is a one-line migration, but it lets a caller learn that a PAN is registered, so decide it
  together with the rate limits on `/kyc/min`.
- **Event Publication Registry and `@ApplicationModuleListener`:** the event is published in-process today.
  Durable delivery (outbox) and Kafka externalisation come in M8. The wallet listener comes in M4.
- **Audit events** for KYC decisions: M10 (audit module).
- **KYC decision e-mail** (FR-N-1): notification module, later.

---

### 5. What to learn from Day 5 Session A

1. **A state machine is a set of guarded methods, not a setter.** `KycCase` has no way to "set status".
   Each transition checks the level and the current status, then changes several fields together
   (status, who, when). Illegal moves are impossible to express, not just discouraged, and the unit
   test can enumerate them all.
2. **Enforce four-eyes twice: in code and in the database.** The service gives a friendly 409. The CHECK
   constraint protects against the bug you haven't written yet, and against someone with psql. And
   watch SQL's three-valued logic: `approved_by <> verified_by` is NULL, which a CHECK *accepts*, when
   `verified_by` is NULL. That is why `ck_kyc_cases_full_four_eyes` exists.
3. **Order the steps so the database serialises the race for you.** "Check, then write" is a race. Put
   the write that hits a unique index first, and the concurrent duplicate blocks on it and fails cleanly.
   Writing the shared `users` row first would have turned the same race into an optimistic-lock 500.
   This is the same lesson as Day 4's JIT provisioning, with step order as the lever.
4. **One transaction across modules, in-process, is allowed and useful.** kyc calls
   `IdentityApi.recordVerifiedPhone` inside its own transaction. If the phone belongs to someone else,
   the KYC case disappears with the rollback, so you never get "approved, but the phone failed".
   (CLAUDE.md forbids *remote* calls while holding locks, not module API calls.)
5. **Time zones are business rules.** "18 today" depends on whose today. With a fixed `Clock` at
   20:00 UTC, it is already the next day in India, and `MinKycServiceTest` proves the birthday counts.
   Never call `LocalDate.now()` in production code; derive it from the injected `Clock` and a named zone.
6. **Validate at every layer, with different jobs.** Bean Validation at the edge gives field-level 400s
   without echoing values. The aggregate re-checks its invariants (a PAN regex in the domain). The
   database checks the rest. Configuration validates itself at startup (min age ≥ 18).
7. **Test that events are published *and* not published.** The Modulith `Scenario` waits for the event
   asynchronously, so the test will still work when M8 moves to transactional listeners.
   `@RecordApplicationEvents` proves a failed submission emits nothing, which matters once a wallet is
   created from this event.
