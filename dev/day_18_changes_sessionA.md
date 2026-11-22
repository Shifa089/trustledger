# Changes

## DAY18 Session A (M9, FR-AML-3/4): freeze requests, AML case workflow and the STR export

**Goal:** finish the behaviour side of M9 (SPEC FR-AML-3, FR-AML-4, §5 "AML service", §8 M9):
1. a HIGH alert publishes `AccountFreezeRequestedV1` (walletId, caseId, rule code) with a `KafkaTemplate`, and an
   answer to "is an outbox needed here?";
2. a core-app consumer that freezes the wallet with source AML through `WalletApi`, idempotent by `eventId`;
3. case endpoints: list, get, assign, close (FALSE_POSITIVE + notes), escalate; illegal transitions are 409;
   `GET /aml/cases/{id}/str-report` as CSV with masked identifiers;
4. `docs/adr/0007-separate-aml-service.md` (it existed since Day 16, so it was amended).

Branch: `feature/day18-aml-freeze-and-cases` (created from `master`), merged into `master`.

Decisions approved in the plan:
- **Yes, an outbox is needed.** The `RuleEngine` raises alerts inside the consumer's DB transaction while holding
  the wallet's advisory lock. Sending there breaks "no remote calls under locks" and can announce a freeze for a case
  that then rolls back; sending after the commit loses the request for good when the send fails, because
  `processed_events` already marks the event as handled. So: a small hand-written outbox (`aml.freeze_requests`) and
  a relay that sends with a `KafkaTemplate` after the commit. Not Spring Modulith's registry: this service has no
  modules, and it would be a lot of machinery for one event type.
- **Listener in `wallet.messaging`, through a new `WalletApi.freezeForAml`.** core-app's first Kafka consumer.
- **Only the assigned officer closes or escalates**, always with notes; **closing does not unfreeze** the wallet
  (that stays a deliberate officer action in core-app).

Two changes from the plan:
- The plan put the dead-letter `KafkaTemplate` of core-app's consumer in a bean, like risk-aml-service's. In core-app
  that would have switched off Boot's auto-configured `KafkaTemplate`, and the Modulith outbox (ADR 0005) would have
  started sending through a JSON serializer (base64 bytes on the wire). It is built inside the recoverer bean instead,
  with a comment saying why.
- The case list takes `page` and `size` parameters checked against `trustledger.aml.cases.max-page-size` instead of
  a Spring `Pageable`: the list is always oldest first, so a client-supplied `sort` has nothing to do.

---

### 1. The outbox in risk-aml-service (FR-AML-3)

- **`V4__aml_create_freeze_requests.sql`**: `aml.freeze_requests(id, alert_id UNIQUE → alerts, case_id → cases,
  wallet_id, rule_code, correlation_id, requested_at, published_at, attempts)`. Partial index on `requested_at
  WHERE published_at IS NULL` for the relay; checks on the rule code, attempts and `published_at >= requested_at`.
- **`FreezeRequest`** (entity, `cases.domain`): `forAlert(alert, correlationId, now)` accepts HIGH alerts only;
  `toV1()` builds the contract with the row's ID as `eventId`, so every re-send carries the same one. JPA only
  inserts and reads it (`published_at` and `attempts` are not insertable/updatable from JPA).
- **`RuleEngine`**: after saving a HIGH alert it saves its `FreezeRequest` in the same transaction, with the
  correlation ID from the MDC (set by the Kafka listener for the record being processed). MEDIUM alerts and
  suppressed repeat hits write nothing.
- **`PendingFreezeRequests`** port + **`JdbcPendingFreezeRequests`**: `claim(limit)` is
  `SELECT ... WHERE published_at IS NULL ORDER BY requested_at LIMIT n FOR UPDATE SKIP LOCKED`; `markPublished`,
  `markFailed` (both count an attempt).
- **`FreezeRequestRelay`** (`@Transactional`): claims a batch, sends each request and waits for the ack, then marks
  it published. On a failed send: one more attempt, stop the run (the broker is probably down), everything stays
  pending. **`FreezeRequestRelayJob`** runs it with `@Scheduled(fixedDelay = interval)`; `SchedulingConfig` turns on
  scheduling unless `trustledger.aml.scheduling.enabled=false`. No ShedLock: `SKIP LOCKED` already makes several
  instances safe.
- **`KafkaFreezeRequestPublisher`** (`cases.messaging`): key = wallet ID, JSON value, `__TypeId__` =
  `com.trustledger.events.AccountFreezeRequestedV1`, `eventId` and `correlationId` headers; `send(...).get(timeout)`.
  Its `freezeRequestTemplate` (JSON serializer with type info) is built in `KafkaConsumerConfig`, which also creates
  the freeze topic locally and in tests.
- **Config** `trustledger.aml.freeze-relay.*` (`FreezeRelayProperties`): `interval: 1s`, `batch-size: 50`,
  `send-timeout: 10s`, validated at startup (interval and timeout in (0, 1m], batch 1..500).

### 2. The consumer in core-app (FR-AML-3)

- **`V18__wallet_create_processed_events.sql`**: `wallet.processed_events(event_id PK, topic, processed_at)`, the
  wallet module's inbox.
- **`ProcessedEventRepository`** port + **`JdbcProcessedEventRepository`** (`INSERT ... ON CONFLICT DO NOTHING`).
- **`WalletApi.freezeForAml(eventId, walletId, caseId, ruleCode)`** → **`AmlFreezeOutcome`**
  `{FROZEN, ALREADY_FROZEN, WALLET_CLOSED, DUPLICATE}` (new public enum). `WalletApiImpl` delegates to
  **`WalletComplianceService.freezeForAml`**, one transaction:
  1. rule code must look like `R1..R99` (else `IllegalArgumentException`, not retried);
  2. claim the event ID; already there → `DUPLICATE`;
  3. lock the wallet (`NOT_FOUND` rolls the claim back → retried → DLT);
  4. FROZEN → `ALREADY_FROZEN` (an officer's freeze and reason stay); CLOSED → `WALLET_CLOSED`;
  5. otherwise `wallet.freeze("AML case <caseId>, rule <rule>", AML, null, now)`, flush, publish `WalletFrozen`
     (source AML). The notification mail says neither reason nor source (FR-N-1); `WalletFrozenV1` goes back to AML.
- **`AccountFreezeRequestedListener`** (`wallet.messaging`): `@KafkaListener` on the contract topic, puts a safe
  `correlationId` header into the MDC, calls `WalletApi`. The method is `void`: see "What to learn" 3.
- **`shared.events.KafkaConsumerConfig`** + **`KafkaConsumerProperties`** (`trustledger.events.consumer.retry.*`,
  3 attempts, 1 s then 2 s): `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` to `<topic>.DLT`;
  `ClassCastException` and `IllegalArgumentException` are not retried. Consumer settings in `application.yml`
  (`group-id: core-app`, `ErrorHandlingDeserializer` → `JacksonJsonDeserializer`, trusted package
  `com.trustledger.events`, type headers). `KafkaTopicsConfig` also creates
  `trustledger.aml.account-freeze-requested.v1.DLT` (`DEAD_LETTER_TOPICS`).

### 3. Case workflow (FR-AML-4, risk-aml-service)

- **`AmlCase`**: `assign(assignee, now)` (OPEN → INVESTIGATING, or reassign while INVESTIGATING),
  `closeAsFalsePositive(officer, notes, now)` and `escalate(officer, notes, now)` (INVESTIGATING → finished, assignee
  only, notes 1..2000 characters, stripped). Errors: `INVALID_STATE_TRANSITION` (409), `NOT_CASE_ASSIGNEE` (409),
  `VALIDATION_FAILED` (400). The error message names the status, never the notes.
- **Error model copied from core-app** (ADR 0007: the services share contracts only): `shared.ErrorCode`,
  `shared.TrustLedgerException`, `shared.web.GlobalExceptionHandler` + `ProblemDetails` (RFC 9457 with `errorCode`,
  optimistic-lock races → 409 `CONCURRENT_MODIFICATION`, no rejected values echoed), `shared.web.ApiVersioningConfig`
  (`/api/v1/...`, `/api/v2/...` → 400). `spring-boot-starter-validation` added to the AML pom (the same starter
  core-app uses; no new vendor).
- **`AmlCaseService`** (application): list (oldest first; filters `status`, `walletId`; alert count and highest
  severity per case), get, assign (defaults to the caller), close, escalate, STR report. Logs IDs only.
- **`CaseViews`**: `CaseSummary`, `CasePage`, `CaseDetail` (alerts, freeze requests with `publishedAt`, and the
  wallet's facts from first alert − 24 h to the last alert, at most 500), `StrReport`. Amounts as decimal strings.
- **`AmlCaseController`** (`/aml/cases`): `GET`, `GET /{id}`, `POST /{id}/assign` (optional body
  `{assigneeId}`), `POST /{id}/close` (`{resolution: FALSE_POSITIVE, notes}`), `POST /{id}/escalate` (`{notes}`),
  `GET /{id}/str-report`. The officer is the token's `sub` (`Callers.officerId`; a non-UUID subject is 403).
- **Config** `trustledger.aml.cases.*` (`CaseProperties`): `fact-lookback: 24h`, `max-facts: 500`,
  `max-page-size: 100`.
- **Security**: the `/api/*/aml/**` wildcard was replaced by one rule per endpoint (method + path); the chain still
  ends in `denyAll()`. `/aml/alerts` (SPEC §5, not built) is now denied.

### 4. STR export

- **`StrReportCsv`** (pure): header `record_type,case_id,case_status,wallet,officer,occurred_at,rule_code,severity,
  direction,source,counterparty,reference,amount,currency,details`, then one CASE, n ALERT and n FACT rows, CRLF.
- Masking (`shared.IdMasking`): wallet, counterparty, reference and officer IDs → `****` + last 4 characters; UUIDs
  inside alert details and notes too. The case ID stays whole (the report's own reference).
- CSV injection: a cell starting with `= + - @`, tab or CR gets a leading `'`; commas, quotes and line breaks are
  quoted with doubled quotes.
- Only for ESCALATED cases (409 otherwise). `text/csv;charset=UTF-8`,
  `Content-Disposition: attachment; filename="str-<caseId>.csv"`, `Cache-Control: no-store`.
- `shared.Amounts.decimal(paise)` for amounts.

### 5. Tests

**risk-aml-service, unit (new or changed):**
- `AmlCaseWorkflowTest` (12): assign, reassign, close, escalate; every illegal step (OPEN → finished, finished →
  anything) is `INVALID_STATE_TRANSITION`; non-assignee is `NOT_CASE_ASSIGNEE`; notes null/blank/too long refused, at
  the limit accepted; the error message never contains the notes.
- `FreezeRequestTest` (4): fields from the alert, `toV1()` uses the row ID and is stable, MEDIUM refused, unsaved
  alert refused.
- `RuleEngineTest` (+3): a HIGH alert writes a freeze request with the MDC correlation ID; MEDIUM doesn't; a
  suppressed repeat doesn't.
- `FreezeRequestRelayTest` (3): send then mark published, in order; a failed send marks one attempt, publishes
  nothing and stops the run; nothing pending sends nothing.
- `StrReportCsvTest` (5): exact lines with CRLF; no full wallet/transfer/officer ID anywhere; formula
  neutralisation; quoting; notes with a formula and a comma stay one cell.
- `MaskingAndAmountsTest` (3), `CasePropertiesTest` (3).

**risk-aml-service, integration (Testcontainers PostgreSQL + Kafka):**
- `FreezeRequestPublishingIT` (3): three ₹46,000 transfers sent **over Kafka** produce exactly one
  `AccountFreezeRequestedV1` keyed by the sender, with the outbox row's ID as `eventId`, the case ID, R2,
  `__TypeId__` and `correlationId` headers; the row is marked published; the receiver gets nothing. An R3 (MEDIUM)
  alert publishes nothing. Extra relay runs don't send a published request again.
- `AmlCaseControllerIT` (10): list with filters and counts; detail with alerts, freeze request and facts; bad
  query values, unknown case (404); assign to self / to another officer / reassign; close as false positive, then
  assign is 409; every illegal transition 409 with the right `errorCode`; body validation (missing notes, missing
  or wrong resolution, too long, blank); CSV download (headers, rows, masking, no full IDs); CSV for a non-escalated
  case is 409.
- `AmlSecurityIT` (6, rewritten as a matrix): for each of the six endpoints, 401 without a token, 403 for CUSTOMER,
  AUDITOR and ADMIN, past security for COMPLIANCE_OFFICER; unlisted paths and wrong methods are 403 even for an
  officer; `/api/v2` is 400.

**core-app, unit:**
- `WalletComplianceServiceTest` (+6): AML freeze (source AML, no officer, reason, `WalletFrozen`); duplicate changes
  nothing and doesn't even lock; already frozen keeps the officer's freeze; closed wallet; unknown wallet
  `NOT_FOUND`; malformed rule codes refused before anything is claimed.
- `AccountFreezeRequestedListenerTest` (2): calls `WalletApi` with the correlation ID in the MDC and removes it
  afterwards; unsafe or missing header ignored.
- `KafkaConsumerPropertiesTest` (2): 3 attempts = 2 retries with the configured backoff; unsafe values fail startup.

**core-app, integration:**
- `AccountFreezeRequestedConsumerIT` (5): a request freezes the wallet with source AML and the right reason, marks
  the event processed and sends `WalletFrozenV1` (source AML, AML's correlation ID); a re-sent request after an
  officer's unfreeze leaves the wallet ACTIVE (ordered behind it on the same partition, an unreadable marker proves it
  was handled); a wallet frozen by an officer keeps that freeze; an unreadable record goes to the DLT with its
  original bytes; an unknown wallet is retried, dead-lettered and not marked processed.
- `KafkaTopicsIT` (+1): the DLT topic exists.

### 6. Docs

- `docs/adr/0007-separate-aml-service.md`: amendment "freeze requests, case workflow and the STR export" (options
  table for the outbox question, the inbox, workflow rules, masking, consequences).
- `docs/adr/0005-outbox-with-spring-modulith.md`: one sentence corrected (AML's outbox is hand-written, not
  Modulith's).

### 7. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS**.
- event-contracts: 31 unit tests (unchanged).
- core-app: 1092 unit tests and 619 integration tests (were 1082 and 613).
- risk-aml-service: 99 unit tests and 48 integration tests (were 66 and 35).

### Still open (next M9 session)

- `GET /aml/alerts` with filters (rule, severity, wallet), SPEC §5; denied until built.
- A test that runs both applications end to end (risk-aml-service events in → core-app wallet FROZEN). Today the two
  halves are tested separately against the same contract and message shape.
- Alerting on old pending freeze requests and on DLT traffic (M12), a metric for relay attempts.
- Audit events for case transitions and AML freezes (M10).

### What to learn from this

1. **"Do I need an outbox?" is answered by listing the failure cases, not by taste.** For each way of sending, ask
   two questions: what happens if the send succeeds and the commit fails, and what happens if the commit succeeds and
   the send fails. Here, the input event's de-duplication (`processed_events`) is what made "send after commit"
   unsafe: the retry that would have saved you is switched off by your own idempotency.
2. **An outbox and an inbox are the two halves of exactly-once *effect*.** The producer guarantees at least once
   (same `eventId` on every re-send); the consumer removes duplicates by `eventId` in the same transaction as the
   effect. Neither half is enough alone. And de-duplicate by event ID, not by state: "the wallet is already active"
   would have let an old re-send undo an officer's unfreeze.
3. **Framework conventions hide in return types and bean presence.** A `@KafkaListener` that returns a value is a
   request/reply listener and failed every record ("Record in retry") with nothing in the logs that said why; a
   `KafkaTemplate` bean silently replaces Boot's auto-configured one, which another feature depended on. When adding
   infrastructure to an app that already has some, check what your new bean switches off.
4. **Exports are an attack surface.** A CSV opened in a spreadsheet runs formulas; free-text notes are attacker- or
   typo-controlled input. Mask identifiers, neutralise `= + - @`, quote properly and say `no-store`.
5. **Workflow rules belong in the aggregate, and HTTP status codes in one mapper.** `AmlCase` throws
   `INVALID_STATE_TRANSITION`/`NOT_CASE_ASSIGNEE`; the controller has no `if`. That made the 12 unit tests cover the
   whole state machine, and the IT only checks the mapping to 409.
