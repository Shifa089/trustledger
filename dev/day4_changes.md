# Changes

## DAY4 — Milestone M2 (part 2): identity module, consents, rate limiting

**Goal:** the `identity` module (FR-ID-2, FR-ID-3): `identity.users` and `identity.consents` tables
with encrypted personal data and blind indexes, just-in-time (JIT) user provisioning that survives two
"first" requests arriving together, `GET /api/v1/me` (masked), `GET/POST /api/v1/me/consents`
(append-only history), the public `IdentityApi`; plus Bucket4j + Redis rate limiting (20/min per user
on money endpoints, 100/min elsewhere, 429 `RATE_LIMITED`) with `POST /transfers` and `POST /topups`
as 501 stubs. ADR 0002 updated with the JIT design. This completes M2.

Branch: `feature/m2-identity` (created from `master` after PR #3; not committed yet).

---

### 1. How to verify Day 4

Run these from `dev/`. Start **Docker Desktop** first.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build: 328 core-app unit/slice tests, 145 core-app ITs (real PostgreSQL, Redis and Keycloak in Testcontainers), AML IT, Modulith check, JaCoCo. Same as CI. |
| 2 | `./mvnw -pl core-app verify -DskipUnitTests -Dit.test='JitProvisioningIT,RateLimitIT'` | Just the two "interesting" ITs: the provisioning race and the 21st-request 429. |
| 3 | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak` | Local stack for the live check. No realm change today, so no `--force-recreate` needed. |
| 4 | `./mvnw install -DskipTests` then `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local` | Starts core-app; Flyway applies **V2** to your local database on startup (log: `now at version v2`). |
| 5 | Run the new requests in `http/auth.http` (customer1 block), or the curl lines in the README | `GET /me` (masked, creates the user), consents (grant, withdraw MARKETING, try to withdraw TERMS → 400), 21 × `POST /transfers` → twenty 501 then 429. |
| 6 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "select email_enc, email_hash from identity.users"` | See for yourself: only ciphertext and a hex HMAC, never the e-mail. |
| 7 | Same psql, `update identity.consents set granted = false;` | Fails with `identity.consents is append-only`: the database itself protects the history. |

**Results on Day 4:** `./mvnw clean verify` BUILD SUCCESS: core-app 328 unit/slice tests and
145 ITs (new: `ConsentsIT` 10, `JitProvisioningIT` 8, `IdentityApiIT` 7, `RateLimitIT` 7,
`IdentityMigrationIT` 6, `MeControllerIT` 5; `SecurityMatrixIT` 52 → 70), AML IT 1/1,
ModularityTests green. Line coverage: `identity` 100%, `identity.application` 100%, `identity.domain`
98.9%, `identity.web` 86.4%, `shared.ratelimit` 90.8%. Live check against the compose stack with a
real customer1 token: `/me` returned `c***@example.test` / `T*** C*** O***` and created the row;
consents 201/201/400 as expected; transfers 501 ×20 then **429 with `Retry-After: 59`**; the database
holds only ciphertext; an UPDATE on consents is rejected by the trigger; the app log contains no e-mail
or name.

---

### 2. What changed and why

#### Database (`V2__identity_create_users_and_consents.sql`)

| Object | Change | Why |
|---|---|---|
| `identity.users` | `id`, `keycloak_subject` (**unique**), `email_enc` + `email_hash`, `phone_enc` + `phone_hash` (**unique**), `full_name_enc`, `status` (ACTIVE/ERASED), `created_at`, `updated_at`, `version` | SPEC §4. Personal data only as AES-GCM ciphertext (FR-PR-1); the HMAC blind index makes equality lookups possible without plaintext. The unique subject is what makes JIT race-safe; the unique phone hash means one account per mobile number (P2P lookup by phone needs that). |
| ″ | Checks: status values; `(x_enc IS NULL) = (x_hash IS NULL)` for e-mail and phone | A ciphertext without its index (or the reverse) would make a user unfindable or an index point at nothing. |
| ″ | Index on `email_hash` (not unique) | Keycloak owns e-mail uniqueness and an e-mail can change there; we only need lookups. |
| `identity.consents` | `id`, `seq` (identity column), `user_id` (FK to users, same schema), `purpose` (TERMS/PRIVACY/MARKETING), `policy_version`, `granted`, `captured_at`, `ip`, `created_at`; index `(user_id, purpose, seq DESC)` | FR-ID-3: who agreed to which version, when, from where. "Latest wins" = highest `seq`, which is exact even if two rows share a timestamp. |
| ″ | Trigger rejecting UPDATE, DELETE and TRUNCATE | Consent is evidence: append-only enforced by the database, not just by convention. (M10 erasure may need to blank `ip`; that migration will relax it deliberately.) |
| Hash columns | `varchar(64)`, not `char(64)` | Hibernate's `ddl-auto=validate` maps `String` to varchar; `char` (bpchar) would fail validation. |

#### Identity module (`com.trustledger.core.identity`)

| File | Change | Why |
|---|---|---|
| `IdentityApi`, `UserView` (base package = public API) | `findUserId(subject)`, `findByPhone(phone)`; `UserView(id, status, maskedFullName, maskedPhone)` with a nested `Status` enum | Other modules (payments: recipient by phone; KYC) get IDs and masked values only, never the entity or plaintext. Modulith makes sub-packages internal. `findByPhone` accepts `+91 …`, `0…`, dashes; malformed input gives empty, never an exception that reveals why. |
| `domain/User` | Entity with `@Getter`, protected no-args constructor, `@Convert(EncryptedStringConverter)` on the three personal fields, UUIDv7 IDs (`@UuidGenerator(VERSION_7)`), `@Version`; state changes only via `provision(...)` and `changePhone(...)` | CLAUDE.md entity rules. `changePhone` requires the normalised 10-digit form + its index; it is the hook for minimum KYC (M3). |
| `domain/Consent`, `ConsentChoice`, `ConsentPurpose` | `@Immutable` entity created by `Consent.record(...)`; `ConsentPurpose.required()` (TERMS, PRIVACY) | Hibernate never issues an UPDATE for a consent; the required flag lives in one place. |
| `domain/ConsentPolicy` | Pure rules: each purpose once; version must equal the published one; TERMS/PRIVACY cannot be withdrawn; after the request TERMS and PRIVACY must be granted for the **current** version. `requiredGiven(...)` | All the business rules in one unit-testable class. The "current version" rule means a policy update automatically asks users to re-accept. |
| `domain/UserRepository`, `ConsentRepository` (ports) + `infrastructure/JpaUserRepository`, `JpaConsentRepository` | Domain interfaces; Spring Data interfaces extend them. Latest-per-purpose via PostgreSQL `DISTINCT ON` | "Infrastructure implements ports": application code depends on the domain interfaces only. |
| `application/UserProvisioningService` | `ensureUser(TokenIdentity)`: read → insert in a **REQUIRES_NEW** transaction with `saveAndFlush` → on `DataIntegrityViolationException` re-read the winner's row | FR-ID-2 race handling (see section 5). Explicit `TransactionTemplate`s because the catch must sit outside the failed transaction. |
| `application/ConsentService`, `ConsentOverview` | `current(userId)`, `record(userId, choices, ip)`: validates all choices first (all or nothing), then appends one row each with the same timestamp, truncated to microseconds | Append-only history; the response timestamp equals what PostgreSQL stores. |
| `application/UserProfileService`, `UserProfile` | Returns the profile **already masked** | Plaintext never leaves the application layer, so a controller cannot return it by mistake. |
| `application/IdentityApiImpl` | Package-private implementation of `IdentityApi` | Only the interface is visible. |
| `application/IdentityProperties` | `trustledger.identity.policy-versions.{TERMS,PRIVACY,MARKETING}`, all required, ≤ 32 chars | Config record, validated at startup (CLAUDE.md: no hard-coded values). |
| `application/TokenIdentity` | `sub`, `email`, `name` from the token; `toString()` masks e-mail and name | The application layer does not depend on Spring Security's `Jwt`; accidental logging cannot leak personal data. |
| `web/JitProvisioningFilter` (+ `IdentityWebConfig`) | Servlet filter after Spring Security and rate limiting: `ensureUser` for every authenticated `/api/**` request; puts `userId` into the MDC; errors become a ProblemDetail 500 | "First authenticated request" means *any* endpoint, not just `/me`. Logs now carry `userId` (visible in the ECS JSON test logs). Registered via `@Bean`, not `@Component`, so `@WebMvcTest` slices don't drag it in. |
| `web/MeController` | `GET /api/v1/me`, `@PreAuthorize("isAuthenticated()")`: `{id, email, phone, fullName, status, roles}` masked | SPEC §5 "Any" role. Also calls `ensureUser` itself, so it is correct even without the filter. |
| `web/ConsentController` | `GET/POST /api/v1/me/consents`, CUSTOMER; body `{"consents":[{purpose, policyVersion, granted}]}` with Bean Validation; IP = `request.getRemoteAddr()`; POST returns 201 | The user always comes from the token, never from the URL or body: no ID to tamper with (no BOLA). |
| `web/TokenClaims` | Reads `sub`, `email`, `name` (or `given_name family_name`) | Standard OIDC claim names. |

#### Rate limiting (`com.trustledger.core.shared.ratelimit`)

| File | Change | Why |
|---|---|---|
| `pom.xml` (parent) | `bucket4j.version` 8.21.0 in dependency management for `bucket4j_jdk17-core` and `bucket4j_jdk17-lettuce` | Bucket4j is on the CLAUDE.md stack list, so no ADR needed. 8.21.0 is the newest release older than two weeks (8.22.0 was 11 days old). Lettuce itself comes from `spring-boot-starter-data-redis`. |
| `RateLimitProperties` | `trustledger.rate-limit.{enabled, default-per-minute: 100, money-per-minute: 20, money-endpoints: ["POST /api/*/transfers", "POST /api/*/topups"], redis-timeout: 500ms}`; entries parsed and validated at startup | Limits and the list of money endpoints are configuration, not code. A typo stops startup instead of silently disabling a limit. |
| `RateLimiter` / `RedisRateLimiter` | Bucket4j `LettuceBasedProxyManager` (compare-and-swap in Redis), **interval refill** (full budget per minute), keys `rl:money:<sub>` / `rl:default:<sub>`, keys expire when the bucket would be full again; connection opened lazily and retried | Redis = one shared budget across all app instances. Lazy connect: the app starts even when Redis is down. |
| `RateLimitConfig` | Own Lettuce `RedisClient` built from Boot's `DataRedisConnectionDetails`; 500 ms connect/command timeout; `REJECT_COMMANDS` while disconnected | Same host/password as Spring's Redis locally and the Testcontainers connection in tests. A slow Redis must not make every API call slow. |
| `RateLimitFilter` | After Spring Security, before JIT provisioning; keyed by token subject; anonymous and non-`/api` requests skipped; 429 ProblemDetail `RATE_LIMITED` + `Retry-After`; `X-RateLimit-Remaining` on every limited response; **fails open** with a WARN if Redis errors | Unauthenticated requests are rejected before spending anyone's budget; abusive users are stopped before touching the database. Per user, not per IP (carrier NAT puts many users behind one IP). Fail-open, as agreed in the plan: correctness of money is guaranteed by idempotency and row locks, not by the rate limiter. |
| `shared/web/FilterOrder` | One place for filter order: correlation ID → security (-100) → rate limit (-90) → JIT (-80) | The order matters and was spread over annotations. |
| `shared/web/ProblemDetailWriter` | Extracted from `ProblemDetailSecurityHandler`; reused by the rate-limit and JIT filters | Errors raised in filters never reach `@RestControllerAdvice`; one writer keeps the JSON identical. |
| `payments/web/PaymentsStubController` | `POST /api/v1/transfers` and `/topups`, CUSTOMER, return 501 `NOT_IMPLEMENTED` | The endpoints must exist for the rate-limit and security rules today; replaced in M6/M7. Built as a response, not thrown, so no stack trace per call. |

#### Shared changes

| File | Change | Why |
|---|---|---|
| `ErrorCode` | `NOT_IMPLEMENTED (501)` | For the stubs. |
| `Masking.name` | `Test Customer One → T*** C*** O***` (code-point safe; blank → `****`) | `/me` and `UserView` need a masked name; FR-PR-1. |
| `BlindIndex.normalisePhone` (public static) | Extracted from `phone(...)` | The stored phone and its index must use the same canonical 10 digits. |
| `SecurityConfig` | URL rules: `/api/*/me/consents` and `POST /api/*/transfers`, `/api/*/topups` → CUSTOMER | Defence in depth, and fixes a real ordering bug (see Fixes). `@PreAuthorize` stays on each method. |
| `application.yml` | `trustledger.identity.policy-versions.*` (2026-11-01) and `trustledger.rate-limit.*` | Secure defaults for every environment. |
| `core-app/pom.xml` | Lombok (optional) + `annotationProcessorPaths`; excluded from the boot jar | First entities. Since JDK 23 annotation processors are not picked up from the classpath automatically. |

#### Tests

| File | What it proves |
|---|---|
| `UserTest` (12), `ConsentTest` (3), `ConsentPolicyTest` (13) | Entity invariants (subject length, ciphertext/index pairs, normalised phone); every consent rule including "policy update forces re-acceptance" and "TERMS/PRIVACY cannot be withdrawn". |
| `UserProvisioningServiceTest` (6) | Deterministic branches with mocks: existing user → no insert; new user → blind index + Clock time; **lost race → re-read the winner**; a violation with no winner is rethrown; `TokenIdentity.toString()` hides e-mail and name. |
| `IdentityPropertiesTest` (4), `RateLimitPropertiesTest` (9) | Bad configuration fails startup. |
| `RateLimitFilterTest` (15) | Bucket choice per method/path, key format, 429 body + `Retry-After` rounding, fail-open, anonymous and non-API skipped, disabled flag. |
| `MaskingTest`, `BlindIndexTest`, `ErrorCodeTest` | Name masking (incl. non-ASCII initials), phone normalisation, 501 code. |
| `IdentityMigrationIT` (6) | Raw SQL: no plaintext anywhere in the row; subject and phone hash unique (many NULL phones fine); consents reject UPDATE/DELETE; purpose and ciphertext/index checks. |
| `JitProvisioningIT` (8) | Any authenticated request creates the user once; unauthenticated creates nothing; **16 threads released by a latch provision the same new subject → exactly one row, one ID** (repeated 5×; the logs show the unique violation was hit and recovered). |
| `MeControllerIT` (5) | Masked fields, no plaintext in the body, staff allowed, token without e-mail/name works, stable ID, 401. |
| `ConsentsIT` (10) | Grant, withdraw MARKETING (history keeps both rows), TERMS withdrawal 400, MARKETING-only first request 400, outdated version 400 (nothing written), malformed bodies 400 with `violations`, officer/admin 403, isolation between users, response timestamp equals stored one. |
| `IdentityApiIT` (7) | `findUserId`; `findByPhone` for four input formats with masked view; unknown/malformed → empty; the stored hash equals the blind index. |
| `RateLimitIT` (7, real Redis) | **20 × 501 then the 21st → 429** ProblemDetail with `Retry-After` and correlation ID; transfers and top-ups share the money bucket; per user; money bucket exhausted but `/me` still works; 101st ordinary request → 429; unauthenticated spends nothing; Redis key exists with a TTL. |
| `SecurityMatrixIT` (52 → 70) | New rows for `/me`, `/me/consents` (GET and POST) and the two stubs across roles. |
| `ApiMockMvc` (test support) | MockMvc with the production filter order, plus mock-JWT helpers with fresh subjects, while still sharing one Spring context (and one set of containers) across all ITs. |
| `SwaggerPublicLocalTest` | Now `@WebMvcTest(controllers = PingController.class)`: the slice used to load every controller and the new ones need services outside the slice. |

#### Docs and developer files

| File | Change | Why |
|---|---|---|
| `docs/adr/0002-keycloak-instead-of-custom-auth.md` | JIT paragraph revised: which claims are copied (once), roles read per token, phone from KYC not the token, race handling, where the filter sits | The ADR already existed from Day 3; it now records the final design. |
| `http/auth.http` | customer1: `/me`, consents GET/POST/withdraw/invalid withdraw, transfer stub (run 21× for 429) | Manual smoke test for Day 4. |
| `README.md` | curl for `/me` and consents; JIT, masking and rate limits in two sentences | Reviewers try the API from the README. |

**Fixes found while testing**

- **`@WebMvcTest` slices broke.** Slices include every `Filter` `@Component`, and the JIT filter needs
  identity services that a slice does not load. Fix: register the filter with `@Bean` in
  `IdentityWebConfig`, and scope the Swagger slice test to `PingController`.
- **500 on the first consent POST: "Lock mode WRITE not supported for read-only entity".** Hibernate's
  `@Generated` (read the DB-assigned `seq` back after insert) is not allowed on an `@Immutable` entity.
  `seq` is only needed in queries, so it is now a plain read-only column (`insertable/updatable=false`).
- **The 101st request still got 200.** With a *greedy* refill, 100/min hands back a token every 0.6 s,
  so a burst taking ~1 s found a fresh token. Switched to an *interval* refill: the full budget returns
  once per minute, which is exactly "N per minute" and deterministic. Trade-off: up to 2N around a
  window boundary.
- **Officer posting an empty body to `/me/consents` got 400, not 403.** `@PreAuthorize` runs after MVC
  has parsed and validated the body, so the caller learned about validation before being told they are
  not allowed. Fix: URL rules for the customer-only endpoints, so authorization happens in the filter
  chain first.
- **POST and GET disagreed on `capturedAt`** (`…408336100Z` vs `…408336Z`, found in the live check).
  Java on Windows has 100 ns precision; PostgreSQL stores microseconds. The capture time is truncated
  to microseconds, and `ConsentsIT.responseTimestampEqualsTheStoredOne` guards it.
- **Ambiguous `findById`** in a test: the Spring Data interface has both `findById(ID)` and the port's
  `findById(UUID)`. Tests (like the application) now use the domain port type.

---

### 3. Machine setup done on Day 4 (not in Git)

- **Branch** `feature/m2-identity` created from `master`.
- **Docker Desktop** started; compose `postgres`, `redis`, `keycloak` running.
- **Local database migrated to V2** by the live check (and contains customer1 plus 4 consent rows).
  To start fresh: `docker compose -f infra/docker-compose.yml down -v` (deletes all local data).
- No `.env` changes: the new settings have defaults in `application.yml`.

### 4. Deferred

- **Phone number:** set by minimum KYC (M3) through `User.changePhone`, exposed to KYC via an
  `IdentityApi` method or an event when M3 needs it. Until then `findByPhone` only finds test users.
- **Syncing e-mail/name changes** from Keycloak after creation: not done; a candidate for a Keycloak
  event listener or a "refresh on mismatch" check later.
- **Caching provisioned subjects** to skip the per-request SELECT: M13 (measure first).
- **Trusted `X-Forwarded-For`** for the consent IP and future per-IP limits behind a load balancer: M14.
- **Redis Sentinel/Cluster and TLS** for the rate limiter (standalone only today; the config rejects others): M14.
- **Lost-race log noise:** Hibernate logs the expected unique violation at ERROR (subject UUID only,
  no personal data). Acceptable for now; can be filtered in the logging config in M12.
- **MapStruct:** not used yet; the few mappings include masking, which reads clearer as code. Revisit
  with the larger DTOs in wallet/payments.
- **Real transfer and top-up endpoints** (with `Idempotency-Key`): M6 and M7 replace the stubs.
- **Ownership checks (BOLA) on resources with IDs:** first needed in M3 (`/kyc/cases/{id}`).

---

### 5. What to learn from Day 4

1. **"Check, then insert" is a race; let the database decide.** Two requests can both read "no
   user" before either inserts. A unique constraint is the only thing that sees both: one insert wins,
   the other fails, catches the error and re-reads. Look at `UserProvisioningService` and
   `JitProvisioningIT.concurrentFirstRequestsCreateExactlyOneUser`, where a `CountDownLatch` releases 16
   threads at the same instant to make the race actually happen.
2. **In PostgreSQL a failed statement poisons its transaction.** After the unique violation, every
   further query in that transaction fails ("current transaction is aborted"). That is why the insert
   runs in its own `REQUIRES_NEW` transaction and the re-read in a fresh one, and why
   `saveAndFlush` (fail now, inside our try/catch) beats `save` (fail later, at commit).
3. **Encryption hides data; a blind index finds it again.** AES-GCM with a random IV makes the same
   phone number encrypt differently every time, so you cannot `WHERE phone_enc = ?`. A keyed HMAC of the
   normalised value is deterministic, so you can, without storing the number. Normalise first, or
   `+91 98765 43210` and `9876543210` become different users.
4. **Append-only means the database refuses, not "we promise not to".** `@Immutable` stops Hibernate;
   the trigger stops everyone else (a script, a psql session, a future bug). A withdrawal is a new row,
   so the history answers "what did this user agree to on 3 March?", which is the point of consent records.
5. **Filter order is security.** Correlation ID first (so even a 401 is traceable), then
   authentication, then rate limiting (only known users spend budget, before the DB is touched), then
   provisioning. And authorization must run *before* the body is parsed, or a 400 tells an unauthorized
   caller something about the endpoint.
6. **Know your rate-limit algorithm.** Greedy refill (smooth, tokens trickle back) and interval refill
   (fixed budget per window) both say "100 per minute" but behave differently under a burst. The test
   that failed was the useful one: it showed the spec meant the second.
7. **Fail open vs fail closed is a decision, not an accident.** If Redis dies, should every customer be
   locked out? For a throughput limiter, no: correctness lives elsewhere (idempotency, locks, limits in
   the DB). Short timeouts plus a WARN log make that choice explicit and observable.
8. **Mask in the layer that owns the data.** `UserProfile` and `UserView` are masked before they leave
   the application layer, and `TokenIdentity.toString()` masks itself, so a later controller or log line
   cannot leak plaintext by accident.
