# Changes

## DAY2 — Milestone M1: shared foundation

**Goal:** the building blocks every module uses, all in the OPEN Spring Modulith module
`com.trustledger.core.shared` (core-app): money, the error model, correlation IDs, time,
structured logs, field encryption, blind indexes, masking, validators, OpenAPI, `/api/v1`
versioning and the per-module database schemas. Still no business features.

Branch: `feature/m0-skeleton` (not committed yet; suggested: `git switch -c feature/m1-shared`
before committing, one branch per milestone).

---

### 1. How to verify Day 2

Run these from `dev/` (the Maven project root). Start **Docker Desktop** first: the ITs use
Testcontainers.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw verify` | Full build: 222 unit/slice tests, 8 core-app ITs on Testcontainers, the Modulith boundary check, JaCoCo. Same command as CI. |
| 2 | `./mvnw -pl core-app test -Dtest='MoneyTest,AesGcmCipherTest,ValidatorsTest'` | Fast loop for the pure logic (no Spring context, no Docker). |
| 3 | Check `.env` has `TRUSTLEDGER_CRYPTO_KEY` and `TRUSTLEDGER_CRYPTO_INDEX_KEY` | core-app **refuses to start** without two different base64 32-byte keys. They were generated into your `.env`; on a new machine run `openssl rand -base64 32` twice. |
| 4 | `docker compose -f infra/docker-compose.yml up -d`, then `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local` | Flyway applies `V1__shared_create_schemas.sql` to the compose Postgres and the app starts with the `.env` keys. |
| 5 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c '\dn'` | Lists the 8 module schemas (`identity` … `notification`) plus `public`. |
| 6 | `curl -i -H "X-Correlation-Id: demo-1" localhost:8080/api/v1/anything` | The response echoes `X-Correlation-Id: demo-1` and the log line shows `[demo-1]`. Until M2 adds real security rules, Spring's default security answers 401. |
| 7 | Run core-app **without** the `local` profile (or look at any IT output) | Console logs are ECS JSON lines with a `correlationId` field. |
| 8 | Remove one crypto key from `.env` and start the app | Startup fails with `trustledger.crypto.key is required` — proof the keys are validated at startup. Put it back afterwards. |

**Results on Day 2:** `./mvnw clean verify` BUILD SUCCESS — core-app 222 unit/slice tests and
8 ITs (`TrustLedgerApplicationIT` 2, `ApiVersioningIT` 4, `FlywaySchemasIT` 2), AML IT 1/1,
ModularityTests green.

---

### 2. What changed and why

#### Session A — Money, error model, correlation IDs, Clock, structured logging

| File | Change | Why |
|---|---|---|
| `shared/package-info.java` | `@ApplicationModule(type = OPEN)` | Shared kernel: other modules may use its sub-packages (`web`, `config`, `crypto`, …). It depends on no business module. |
| `shared/Money.java` | Record `(long amountMinor, Currency)`. Non-negative invariant, only 2-minor-digit currencies, `plus`/`minus` via `Math.addExact`/`subtractExact`, comparisons, `parse` (strict regex + `BigDecimal.longValueExact`), `toDecimalString` | Money is exact integer paise; nothing is ever rounded. Bugs (negative, mismatch, overflow) fail loudly as 500s. Bad user input (`parse`) is a 400 `VALIDATION_FAILED`. |
| `shared/ErrorCode.java` | SPEC §5 codes with HTTP status, title, and type URI `https://trustledger.dev/errors/<kebab-case>`. Adds `UNSUPPORTED_REQUEST` (405/406/415) and `INTERNAL_ERROR` (500) | One stable, client-facing vocabulary; every error response carries an `errorCode`. |
| `shared/TrustLedgerException.java` | `RuntimeException(ErrorCode, detail)` | Base for all business errors; the detail is client-visible, so no personal data. |
| `shared/web/GlobalExceptionHandler.java` | Extends `ResponseEntityExceptionHandler`; every error is a ProblemDetail with `type, title, status, detail, instance, errorCode, correlationId`; `violations` = `{field, message}`; maps Spring Security `AccessDenied`/`Authentication` exceptions; unknown errors return a generic 500 | RFC 9457 format from SPEC §5. Rejected values are never echoed (could be a PAN). Stack traces are logged on the server only. |
| `shared/web/CorrelationIdFilter.java` | Highest-precedence filter: accepts `X-Correlation-Id` matching `[A-Za-z0-9-]{1,64}`, otherwise generates a UUID; MDC + response header; `MDC.remove` in `finally` | Runs before Spring Security so 401/403 carry the ID too. The charset check blocks log injection (CR/LF). |
| `shared/config/ClockConfig.java` | `Clock.systemUTC()` bean | Tests can control time; no `Instant.now()`. |
| `application.yml` | Second YAML document `on-profile: "!local"` → `logging.structured.format.console: ecs` | JSON logs everywhere except on the developer machine; MDC fields are included automatically. |
| `application-local.yml` | `logging.pattern.correlation: "[%X{correlationId:-}] "` | Readable local logs that still show the correlation ID. |
| Tests | `MoneyTest` (48), `ErrorCodeTest` (31), `TrustLedgerExceptionTest`, `ClockConfigTest`, `CorrelationIdFilterTest` (9), `GlobalExceptionHandlerTest` (9, `@WebMvcTest` + test-only controller + permit-all test security), `TrustLedgerApplicationIT` + ECS assertion | SPEC M1 "done when": overflow, negative, mismatch, parse `"1500.50"`, reject `"1.005"`, and the MockMvc error-shape test. |

#### Session B — Encryption, blind index, masking, validators, OpenAPI, versioning, schemas

| File | Change | Why |
|---|---|---|
| `pom.xml`, `core-app/pom.xml` | `springdoc-openapi-starter-webmvc-ui` 3.2.0 (version property `springdoc.version` in the parent) | OpenAPI from SPEC M1. Not in the CLAUDE.md stack, so recorded in **ADR 0010**; 3.2.0 is built against Boot 4.1.2. |
| `docs/adr/0010-springdoc-openapi.md`, `CLAUDE.md` | New ADR; springdoc added to the stack line | CLAUDE.md requires an ADR for every new dependency. (0001–0009 are reserved by SPEC §11.) |
| `shared/crypto/CryptoProperties.java` (+ `CryptoConfig`) | `@ConfigurationProperties("trustledger.crypto")` record `(key, indexKey)`; constructor checks present, base64, exactly 32 bytes, and the two keys differ; `toString()` hides keys | A weak or missing key is a startup failure, not a runtime surprise. Separate keys: leaking the index key must not expose the encryption key. |
| `shared/crypto/AesGcmCipher.java` | AES-256-GCM, random 12-byte IV per value, output `base64(iv ‖ ciphertext ‖ tag)`; new `Cipher` per call, shared `SecureRandom`; any failure → `IllegalStateException("Decryption failed")` | GCM gives confidentiality and tamper detection. A fresh IV per value means equal PANs encrypt differently (no frequency analysis). An IV must never repeat under the same GCM key. |
| `shared/crypto/EncryptedStringConverter.java` | JPA `AttributeConverter`, Spring `@Component`; `null` passthrough | FR-PR-1 encryption at rest. Boot lets Hibernate get converters from Spring, so the cipher is injected. Used per field with `@Convert`. |
| `shared/crypto/BlindIndex.java` | HMAC-SHA256 with the index key → 64 hex chars; `email` (trim + lower-case), `phone` (digits only, drop leading `91`/`0`, must be 10 digits), `value(field, v)`; input prefixed with the field type | Equality lookups on encrypted data (`phone_hash` unique in `identity.users`). Normalising first makes `+91 98765-43210` and `9876543210` match; the field prefix keeps indexes of different fields unrelated. |
| `shared/masking/Masking.java` | `pan` → `XXXXX1234X`, `phone` → `******3210`, `email` → `s***@domain.com`; malformed input → `****` | FR-PR-1 masking in responses and logs. A malformed value may still be personal data, so it is never returned as-is. Fixed `***` hides the name's length. |
| `shared/validation/*` | `@ValidPan` (`[A-Z]{3}[ABCFGHLJPT][A-Z][0-9]{4}[A-Z]`), `@ValidIndianMobile` (`[6-9][0-9]{9}`), `@ValidAmount` (reuses `Money.parse`, must be > 0) | SPEC §6 validators. `null` is valid by convention (pair with `@NotNull`). `@ValidAmount` and `Money.parse` share one rule set, so they can never disagree. |
| `shared/web/ApiVersioningConfig.java` | Prefix `/api/v{version}` for controllers under `com.trustledger.core`; `usePathSegment(1, path starts with /api/)`, supported version `1`, version not required elsewhere | Spring Framework 7 API versioning. Configured in Java, not via `spring.mvc.apiversion.*`, because only Java config accepts the path predicate — without it Spring parses segment 1 of every path and rejects `/v3/api-docs` as version "api-docs". The `v` sits in the prefix so springdoc documents `/api/v1/...` (with `/api/{version}` it showed `/api/1/ping`). |
| `shared/config/OpenApiConfig.java` | `OpenAPI` bean with HTTP bearer (JWT) scheme `bearerAuth`, required globally | Swagger UI and clients know every endpoint needs a Keycloak token (SPEC §5). |
| `db/migration/V1__shared_create_schemas.sql` | `CREATE SCHEMA IF NOT EXISTS` identity, kyc, wallet, ledger, payments, audit, privacy, notification | One schema per module (SPEC §4). `aml` belongs to risk-aml-service; `shared`/`reporting` own no tables. Flyway's history table stays in `public`. |
| `application.yml` | `trustledger.crypto.key/index-key: ${TRUSTLEDGER_CRYPTO_KEY:}` / `${TRUSTLEDGER_CRYPTO_INDEX_KEY:}` | Keys only from environment variables (or the `.env` import locally). The empty default turns a missing key into our clear validation message instead of a placeholder error. |
| `.env.example` | Placeholders + `openssl rand -base64 32` instructions | Documents the new secrets without committing them. |
| `src/test/resources/config/application.yml` | Fixed fake keys (32 × `0x01` / `0x02`) | Test-only; Boot merges `classpath:/config/` over `classpath:/`, so the main config is untouched. |
| `shared/web/GlobalExceptionHandlerTest.java` | Request paths now `/api/v1/test/...` | The new prefix also applies to the test controller in the `@WebMvcTest` slice. |
| Tests | `AesGcmCipherTest` (round-trip incl. Unicode/empty, random IV, layout 12 + n + 16, tamper in IV/ciphertext/tag, wrong key, garbage), `EncryptedStringConverterTest`, `CryptoPropertiesTest`, `CryptoStartupTest` (`ApplicationContextRunner`: context fails without/with short keys), `BlindIndexTest`, `MaskingTest`, `ValidatorsTest` (real Hibernate Validator), `ApiVersioningIT` (`/api/v1/ping` 200, `/api/v2/ping` 400 ProblemDetail, `/ping` 404, `/v3/api-docs` path + `bearerAuth`), `FlywaySchemasIT`, test-only `PingTestController` | SPEC M1 "done when": encryption round-trip and tamper detection, masking, validators. `PingTestController` lives in `src/test`, so it never ships. ITs build MockMvc by hand (no `@AutoConfigureMockMvc`) to share one cached context and one set of containers. |

**Fixes found while testing**

- **Docker Desktop not running** → every IT failed with "Could not find a valid Docker
  environment". Not a code problem; start Docker before `./mvnw verify`.
- **Windows file lock**: `mvnw clean` failed while a shell's working directory was inside
  `target/surefire-reports`. Do not `cd` into `target/` before a clean build.
- **springdoc path rendering**: `/api/{version}` was documented as `/api/1/ping`; moving the
  literal `v` into the prefix (`/api/v{version}`) fixed it (see the versioning row above).

---

### 3. Machine setup done on Day 2 (not in Git)

- **`.env`**: added `TRUSTLEDGER_CRYPTO_KEY` and `TRUSTLEDGER_CRYPTO_INDEX_KEY`, two random
  32-byte keys from `openssl rand -base64 32`. Losing the encryption key makes encrypted local
  data unreadable (fine for local test data; on AWS the key comes from KMS, see ADR 0009 in M10).

### 4. Deferred

- Postgres round-trip of `EncryptedStringConverter` through a real entity: with the first
  encrypted column (`identity.users`, M2).
- ProblemDetail 401/403 from the security filter chain (entry point / access-denied handler),
  who may read `/v3/api-docs` and Swagger UI: M2.
- Key rotation (a key-version byte in the ciphertext) and KMS envelope encryption: ADR 0009, M10.
- risk-aml-service must keep its Flyway history table in its own `aml` schema, so it doesn't
  clash with core-app's `public.flyway_schema_history`: M9.
- Disable or restrict springdoc in the `aws` profile: M13.
