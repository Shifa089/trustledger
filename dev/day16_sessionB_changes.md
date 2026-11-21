# Changes

## DAY16 Session B (M9, FR-AML-1): risk-aml-service consumers

**Goal:** start M9 in `risk-aml-service` (SPEC FR-AML-1, §4 `aml` tables, §5 AML endpoints, §8 M9):
1. Flyway migrations for `transaction_facts`, `alerts`, `cases` and `processed_events`;
2. the same Keycloak resource-server security as core-app (converter copied, not shared);
3. consumers for `TransferCompletedV1` (OUT fact for the sender, IN for the receiver) and `TopupCapturedV1` (IN),
   storing `processed_events` and facts in one transaction;
4. `DefaultErrorHandler` (exponential backoff, 3 attempts) and `DeadLetterPublishingRecoverer` to `<topic>.DLT`;
   deserialisation errors straight to the DLT.

Branch: `feature/day16-aml-consumers` (created from `master`), merged into `master`.

Decisions approved in the plan:
- **(`event_id`, `direction`) unique, not `event_id` alone.** One transfer event gives two facts. SPEC §4 is updated.
- **"3 attempts" = 1 try + 2 retries.** SPEC §6 said "after 3 retries"; it now says 3 attempts, like the brief.
- **Security copied, not shared.** `event-contracts` stays dependency-free (ADR 0007).
- **Dead letters go to a partition chosen by Kafka** (by key), so a DLT doesn't need as many partitions as its
  topic.
- **No new dependencies.** `testcontainers-kafka` is now a test dependency of risk-aml-service too (already in the
  Testcontainers BOM and used by core-app).

---

### 1. Schema `aml` (Flyway, risk-aml-service)

Flyway runs with `schemas`/`default-schema: aml` and `create-schemas: true`, so its history table is
`aml.flyway_schema_history`. Locally the service shares core-app's PostgreSQL without touching its tables. Hibernate
defaults to `aml` with `ddl-auto: validate`.

- **`V1__aml_create_processed_events.sql`**: `event_id` (primary key), `topic`, `processed_at`.
- **`V2__aml_create_transaction_facts.sql`**:
  - `id`, `event_id`, `wallet_id`, `counterparty_wallet_id`, `direction` (OUT/IN), `source` (TRANSFER/TOPUP),
    `reference_id` (transfer or top-up ID, for the STR report later), `amount_minor`, `currency`, `occurred_at`,
    `created_at`;
  - unique (`event_id`, `direction`);
  - checks: amount > 0, currency `[A-Z]{3}`, a TRANSFER has a different counterparty wallet, a TOPUP is IN with no
    counterparty;
  - index on (`wallet_id`, `occurred_at`) for the rule windows;
  - **append-only**: a trigger refuses UPDATE, DELETE and TRUNCATE (like core-app's journal).
- **`V3__aml_create_cases_and_alerts.sql`**:
  - `cases`: `id`, `wallet_id`, `status` (OPEN, INVESTIGATING, CLOSED_FALSE_POSITIVE, ESCALATED), `assigned_to`,
    `resolution`, `ai_summary`, timestamps, `version`;
  - **one open case per wallet**: partial unique index on `wallet_id` while OPEN or INVESTIGATING;
  - `resolution` set exactly when the case is finished;
  - `alerts`: `id`, `case_id` (foreign key, same schema), `wallet_id`, `rule_code` (R1-R4), `severity`
    (MEDIUM/HIGH), `details`, `created_at`.

  Tables only; the entities come with the rules session.

### 2. Security (`com.trustledger.aml.shared.security`)

- `Role` and `JwtRoleConverter`: copies of core-app's, with a note to keep them in step.
- `SecurityConfig`: the same `jwtDecoder` (signature, expiry, issuer, `trustledger-api` audience; keys fetched
  lazily; startup fails without issuer or audience), stateless, no CSRF/form/basic login, the same security headers.
  Rules:
  - `/actuator/health/**`: public;
  - `/api/*/aml/**`: COMPLIANCE_OFFICER;
  - other `/actuator/**`: ADMIN;
  - everything else: `denyAll()`.
- `ProblemDetailSecurityHandler`: 401/403 as ProblemDetail with the bearer `WWW-Authenticate` header; never the
  exception message.
- Issuer: the compose Keycloak in the local profile; a placeholder in tests (never contacted).

### 3. Consumers (`com.trustledger.aml.facts`)

- **`TransactionFactListener`** (messaging adapter): one `@KafkaListener` per topic, consumer group
  `risk-aml-service`. It puts the `correlationId` header into the MDC, but only if it is a plain token (no line
  breaks or markup from another system in our logs).
- **`TransactionFactRecorder`** (application service, the only `@Transactional` class), one transaction per event:
  1. `processed_events` insert with `ON CONFLICT DO NOTHING`; no row inserted → `DUPLICATE`, nothing stored;
  2. facts: a transfer → OUT for the sender and IN for the receiver; a top-up → one IN.

  A failure in step 2 rolls back step 1, so a retry starts clean. Logs show event and wallet IDs only.
- **Domain:** `TransactionFact` (immutable entity, UUIDv7 ID, three factory methods, its own checks), `Direction`,
  `FactSource`, and two ports.
- **Infrastructure:** `JpaTransactionFactRepository` (extends the bare `Repository`, so there is no delete) and
  `JdbcProcessedEventRepository` (`JdbcClient`, because JPA has no `ON CONFLICT` insert).
- `ClockConfig`: the injected `Clock`.

### 4. Retries and dead letters (`com.trustledger.aml.shared.kafka`)

- **Reading:** `ErrorHandlingDeserializer` wrapping Spring Kafka's `JacksonJsonDeserializer`. The type comes from
  the `__TypeId__` header that core-app's outbox writes, and **only `com.trustledger.events` is trusted**.
- **`DefaultErrorHandler`**: `ExponentialBackOffWithMaxRetries` with `max-attempts - 1` retries (1 s, 2 s, at most
  10 s). Never retried: `DeserializationException` (Spring's default) and `ClassCastException` (a contract on the
  wrong topic).
- **`DeadLetterPublishingRecoverer`** → `<topic>.DLT`, partition chosen by Kafka. Its producer sends unreadable
  records as their original bytes and read-but-failed ones as JSON, without adding a second type header.
- **`AmlKafkaProperties`** (`trustledger.aml.kafka.*`): retry settings and topic creation, checked at startup
  (attempts 1..10, intervals positive, at most 1 minute, multiplier ≥ 1).
- Topics and DLTs are created only locally and in tests (`topics.create: true`), with core-app's layout (3
  partitions).
- **Bug found by the tests:** the dead-letter producer was first built from `KafkaProperties`, which doesn't have
  the broker address from Testcontainers' `@ServiceConnection`. It tried `localhost:9092`, the DLT send failed, and
  the poison record blocked its partition. It now takes its configuration from Boot's `ProducerFactory`, which has
  the address from connection details.

### 5. Configuration

- `application.yml`: Flyway/JPA schema `aml`; consumer group, `earliest`, the deserializers and trusted package;
  dead-letter producer `acks=all` with idempotence and bounded timeouts; audience `trustledger-api`; retry and
  topic defaults.
- `application-local.yml`: compose Keycloak issuer and Kafka, correlation ID in the log pattern, topic creation on.
- Test `config/application.yml`: placeholder issuer, 50 ms to 200 ms backoff (still 3 attempts), topic creation on.

### 6. Tests

**Unit (27):**
- `TransactionFactTest` (4): the three factory methods and their checks.
- `TransactionFactRecorderTest` (3): transfer → OUT and IN with the right wallets; top-up → one IN; an already
  processed event stores nothing.
- `TransactionFactListenerTest` (2): the correlation ID is read, or ignored when missing or unsafe.
- `AmlKafkaPropertiesTest` (3): 3 attempts = 2 retries of 1 s and 2 s, then stop; defaults; unsafe values refused.
- `JwtRoleConverterTest` (12) and `AudienceValidatorTest` (3): copied with the classes.

**Integration (21; Testcontainers PostgreSQL and `apache/kafka:4.4.0`):**
- `AmlSchemaIT` (6): own schema and history; one fact per event and direction; fact checks; facts append-only;
  one open case per wallet (a closed one doesn't count); case and alert checks.
- `TransactionFactConsumerIT` (8). Records are sent exactly as core-app's outbox writes them (`KafkaTestClient`):
  - a transfer → OUT and IN facts and one `processed_events` row;
  - a top-up → one IN fact;
  - **the same event delivered twice → stored once** (a later event on the same key proves both copies were
    handled);
  - **not JSON → the DLT**, original bytes and headers, `DeserializationException`, nothing stored;
  - a negative amount (the contract's own check) → the DLT;
  - a `__TypeId__` of `java.util.HashMap` → the DLT, never built;
  - a failure that clears on the second attempt → stored, nothing in the DLT;
  - **a failure that never clears → exactly 3 attempts, then the DLT** with the contract as JSON, and no
    `processed_events` row or fact left behind.
- `AmlSecurityIT` (6): no token → 401 ProblemDetail; CUSTOMER, AUDITOR and ADMIN → 403 on the AML API;
  COMPLIANCE_OFFICER gets past security (404: no endpoints yet); unlisted paths refused; health public, the rest of
  actuator ADMIN only; security headers.
- `RiskAmlApplicationIT` (1): unchanged.

### 7. Docs

- `docs/adr/0007-separate-aml-service.md` (new): why a separate service, own schema and Flyway, contracts as the
  only shared code, security copied; the idempotent consumer; retries and the DLT; consequences.
- `docs/SPEC.md`:
  - §4 `aml.transaction_facts` (columns, unique (`event_id`, `direction`), append-only), `aml.processed_events`,
    `aml.cases`/`aml.alerts` constraints;
  - §6 Kafka: 3 attempts, then `<topic>.DLT`; unreadable records at once.

### 8. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS** (core-app 5 min 8 s, risk-aml-service 1 min 18 s).
- event-contracts: 31 unit tests.
- core-app: 1082 unit tests and 613 integration tests (unchanged).
- risk-aml-service: 27 unit tests and 21 integration tests (were 0 and 1).

0 failures, 0 errors.

### Still open (next M9 session)

- Rules R1-R4 as `AmlRule` beans with windowed SQL, `AmlProperties` thresholds, per-rule boundary tests.
- Alerts grouped into one open case per wallet; HIGH publishes `AccountFreezeRequestedV1`; core-app freezes the
  wallet with source AML.
- Case workflow endpoints and STR CSV export.
- End-to-end: 3 transfers of ₹46,000 within 24 hours freeze the sender's wallet.
- No automatic DLT replay yet; alerting on DLT traffic belongs to M12.

### What to learn from this

1. **Idempotency is a database write, not a check.** "Is it processed? If not, process it" has a race between the
   two steps. `INSERT ... ON CONFLICT DO NOTHING` checks and claims in one statement, and putting it in the same
   transaction as the facts means "processed" and "stored" can never disagree.
2. **Classify failures before retrying them.** A database hiccup may clear in a second, so retry with backoff. Bytes
   that aren't valid JSON will never become valid, so send them to the DLT at once. Retrying a poison message only
   blocks the partition behind it.
3. **A DLT is only safe if it works.** Here a misconfigured DLT producer turned every poison message into a stuck
   partition: the error handler couldn't recover, so it kept seeking back to the same record. Test the dead-letter
   path against a real broker, not only the happy path.
4. **Validate at the edge, and trust a short list.** The contract records check themselves when they are built, so
   a bad event fails while being read, and only classes from one package may be built from a message at all.
5. **Copying can be cheaper than sharing.** Two services sharing a "commons" library are coupled by every change to
   it. The Kafka contracts are the real interface; a 40-line role converter is cheaper to copy and test twice.
