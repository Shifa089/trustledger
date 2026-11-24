# ADR 0007: AML as a separate service with idempotent Kafka consumers

- Status: Accepted
- Date: 2026-11-21 (consumers, DLT); amended 2026-11-22 (freeze requests, case workflow, STR export)
- Spec: docs/SPEC.md FR-AML-1 to 4, §4 `aml` tables, §6 "Kafka", §8 M9

## Context

AML monitoring (rules R1-R4, alerts, cases, STR export) watches every money movement, but must never slow one down
or be able to break one. It is also a different job from the wallet: other people review it, its data lives under
different retention rules, and it may later scale or deploy on its own schedule. Everything else in TrustLedger is a
modular monolith (ADR 0001).

## Decision

### A separate Spring Boot service

`risk-aml-service` is its own application (port 8081). It learns about money movements only from Kafka
(`TransferCompletedV1`, `TopupCapturedV1`, written by core-app's outbox, ADR 0005), and it will act only through
Kafka (`AccountFreezeRequestedV1`, which core-app consumes). There are no synchronous calls in either direction, so
an AML outage delays analysis but never a transfer.

- **Own schema `aml`, own Flyway.** Its migrations and history table (`aml.flyway_schema_history`) live in that
  schema, so it can share core-app's PostgreSQL locally without either one migrating or validating the other's
  tables. In AWS it can move to its own database with only a URL change.
- **Shared code is only the contracts.** `event-contracts` holds the versioned Kafka records and nothing else. The
  Keycloak role mapping (`JwtRoleConverter`, `Role`) and the JWT decoder checks are **copied** from core-app rather
  than shared: putting Spring Security into the contracts module would give it dependencies, and a shared
  "commons" library couples two deployables by more than their messages. The copies are small, and each service
  tests its own.
- **Same security as core-app**: one Keycloak realm, the same issuer and `trustledger-api` audience checks, deny by
  default. `/api/*/aml/**` is COMPLIANCE_OFFICER only.

### Idempotent consumers

Kafka delivers at least once (a retry, a rebalance, a crash before the offset commit), and the outbox can resend
after a restart. Each event is therefore handled in one database transaction:

1. `INSERT INTO aml.processed_events (event_id, ...) ON CONFLICT DO NOTHING`. If no row was inserted, the event was
   already handled: stop.
2. Insert its `transaction_facts` (a transfer gives an OUT fact for the sender and an IN fact for the receiver;
   a top-up one IN fact).

Both commit together. A failure in step 2 also undoes step 1, so a retry starts clean. Two concurrent deliveries
of the same event can't both pass step 1: the second insert waits for the first transaction and then inserts
nothing. Facts are unique per (`event_id`, `direction`) as a second safety net, and append-only.

### Retries and the dead-letter topic

- `DefaultErrorHandler` with exponential backoff: **3 attempts in total** (1 s, then 2 s between them; at most
  10 s), configurable in `trustledger.aml.kafka.retry`.
- Then `DeadLetterPublishingRecoverer` publishes the record to `<topic>.DLT` with Spring's `kafka_dlt-*` headers
  (original topic, partition, offset, exception) and the offset moves on. One bad record can't block its
  partition, and nothing is lost: the DLT can be inspected and replayed.
- **Records that can't be read go straight to the DLT.** `ErrorHandlingDeserializer` wraps the JSON deserializer;
  bad JSON, a contract whose own checks fail (for example a negative amount) or a `__TypeId__` outside
  `com.trustledger.events` becomes a `DeserializationException`, which is never retried, since retrying can't
  change the bytes. The DLT gets the original bytes.
- Only classes in `com.trustledger.events` may be built from a message, so a message can't name an arbitrary class.

## Consequences

- Two deployables, two sets of configuration, and the Kafka contracts become a real API: a breaking change needs a
  `...V2` record and topic.
- AML runs slightly behind real time (the outbox and the consumer each add a little delay). Rules look at
  `occurred_at` from the event, not arrival time, so a late event still lands in the right window.
- A record in a DLT needs a person: there is no automatic replay yet. Alerting on DLT traffic belongs to M12.
- The copied security classes can drift from core-app's. Both services test them; a change to roles means changing
  both.

## Amendment (2026-11-22): freeze requests, case workflow and the STR export

### Freeze requests: an outbox in the AML service

FR-AML-3: a HIGH alert must make core-app freeze the wallet, through `AccountFreezeRequestedV1`. The alert is raised
by the `RuleEngine` **inside the consumer's database transaction**, while it holds the wallet's advisory lock. Sending
straight from there with a `KafkaTemplate` was considered and rejected:

| Option | Failure |
| --- | --- |
| Send inside the transaction | A Kafka call while holding the wallet lock (CLAUDE.md forbids remote calls under locks). If the send succeeds and the commit then fails, core-app freezes a wallet for a case that was rolled back. |
| Send after the commit | If the send fails, `processed_events` already says the event was handled, so the Kafka retry skips it and the freeze request is lost for good: a silent gap in AML. |
| Kafka transactions chained with the DB transaction | Still two commits ("best-effort 1PC"), the same edge cases, much more configuration. |
| Spring Modulith's registry, as in core-app (ADR 0005) | Works, but brings Modulith's event infrastructure into a service that has no modules, for one event type. |

**Decision: a small hand-written outbox, drained with a `KafkaTemplate`.**

- `aml.freeze_requests` (V4): one row per HIGH alert (`alert_id` unique), written by the `RuleEngine` in the same
  transaction as the alert. Its `id` is the message's `eventId`, fixed once.
- `FreezeRequestRelay`, scheduled every `trustledger.aml.freeze-relay.interval` (1 s), claims pending rows with
  `SELECT ... FOR UPDATE SKIP LOCKED` (several instances never send the same row at once; no ShedLock needed), sends
  each one and waits for the broker's ack (`send-timeout`), then sets `published_at`. A failed send increments
  `attempts` and ends the run; the rows stay pending.
- The message: key = wallet ID, JSON value with a `__TypeId__` header naming the contract, `eventId` and
  `correlationId` headers (the correlation ID of the Kafka record whose fact tipped the rule).
- Delivery is **at least once**: a crash between the ack and the commit re-sends the row with the same `eventId`.
- The relay holds row locks on *outbox* rows during the send. Nothing else ever waits on those rows, so this is not
  what the "no remote calls under locks" rule is about (wallet and case rows are never locked by it).

### core-app's side: an inbox

`wallet.processed_events` (V18) is the wallet module's inbox. `AccountFreezeRequestedListener` (in
`wallet.messaging`) calls `WalletApi.freezeForAml`, which, in one transaction, claims the `eventId` with
`ON CONFLICT DO NOTHING`, locks the wallet and freezes it with source AML and reason
`"AML case <caseId>, rule <ruleCode>"`. `WalletFrozen` is published as for an officer's freeze (notification mail
without reason or source, and `WalletFrozenV1` back to AML).

- Already FROZEN: the existing freeze stays (an officer's reason is not overwritten). CLOSED: nothing changes.
- A redelivered request is skipped by `eventId`, **not** by wallet state: if an officer has unfrozen the wallet in
  between, a re-sent old request does not freeze it again. A new HIGH alert (a new request) does.
- Unknown wallet: `NOT_FOUND` rolls the claim back, is retried, then goes to the DLT for a person to look at.
- core-app's consumer setup copies this service's: `ErrorHandlingDeserializer` limited to `com.trustledger.events`,
  3 attempts with backoff, `<topic>.DLT`. Its dead-letter `KafkaTemplate` is deliberately **not a bean**: a
  `KafkaTemplate` bean would switch off Boot's auto-configured one, and the outbox externalization (ADR 0005) would
  start sending through a JSON serializer.

### Case workflow

OPEN → INVESTIGATING (`assign`, also used to reassign) → CLOSED_FALSE_POSITIVE (`close`) or ESCALATED (`escalate`).
Every other step is a 409 `INVALID_STATE_TRANSITION`; a lost race on the same case is a 409 from optimistic locking.

- Only the **assigned** officer finishes a case (409 `NOT_CASE_ASSIGNEE` otherwise), always with notes (stored in
  `resolution`, never logged).
- Closing a false positive **does not unfreeze** the wallet. Unfreezing stays a separate, deliberate officer action in
  core-app (`/compliance/wallets/{id}/unfreeze`), with its own reason and audit trail: two systems, two decisions.
- `assigneeId` is not checked against Keycloak: this service has no user directory. Acceptable while every caller is
  a compliance officer; revisit with M10's audit trail.

### STR export

`GET /aml/cases/{id}/str-report` exists only for ESCALATED cases. One CSV table (RFC 4180, CRLF), one row per CASE,
ALERT and FACT. Wallet, counterparty, reference and officer IDs keep only their last 4 characters, including IDs
quoted inside alert details and notes; the case ID stays whole as the report's own reference. Cells starting with
`= + - @`, a tab or CR get a leading `'` (CSV formula injection). Sent with `Content-Disposition: attachment` and
`Cache-Control: no-store`. No CSV library: the writer is 40 lines and unit-tested.

### Consequences

- One more table and a scheduled job in the AML service; a freeze takes up to about one relay interval to leave.
- The case list and every endpoint have an explicit security rule; `/aml/alerts` (SPEC §5) was denied until it was
  built (Day 26 B, see the update below).
- A request stuck in the outbox (broker down) shows as `publishedAt: null` in the case detail; alerting on old
  pending rows belongs to M12.

## Amendment (2026-11-22, Day 21 B): case lifecycle events for erasure

FR-PR-2 refuses a customer's erasure while an AML case on their wallet is open. core-app has to know that, and
there were two ways:

1. **Ask the AML service over REST** with a client-credentials token (a new Keycloak client, a new internal
   endpoint, a secret, an HTTP client with timeouts). Always current, but erasure would then depend on the AML service
   being up, and the check would be a remote call made while the wallet lock is held, or else not atomic with the
   erasure.
2. **Keep a local copy fed by AML events.** Chosen. It reuses the Kafka pipeline and the outbox pattern above, needs
   no new credentials, and turns the check into a local query under the wallet lock.

**Producer.** Opening a case (rule engine) and finishing it (close as false positive, escalate) insert a row into
`aml.case_events` in the same transaction: an outbox shaped like `aml.freeze_requests`. `CaseEventRelay` sends pending
rows as `AmlCaseStatusChangedV1` (`trustledger.aml.case-status-changed.v1`, wallet ID as key) with the same
`SKIP LOCKED` claim and the same settings as the freeze relay. Assignment (OPEN → INVESTIGATING) isn't sent, because
the case stays open either way. Officer notes are never part of the message.

**Consumer.** core-app's `privacy` module upserts `privacy.aml_cases (case_id, wallet_id, open)`. A case only ever
goes from open to finished, so:

- "opened" inserts the case only if it is unknown;
- "finished" sets `open = false` whatever was there, inserting the case if needed.

Applying the events twice, or in the wrong order, ends in the same state. No `processed_events` table is needed, and
retries and the DLT work as for freeze requests. The consumer decides on the contract's `open` flag, not on the status
name, so a status added later on the AML side needs no change in core-app.

**Accepted trade-off: eventual consistency.** A case opened a moment ago may not have arrived yet. That is
acceptable because AML cases come from transfers the AML service already processed asynchronously, and because
erasure never touches the ledger, the transfers or the audit trail an investigation relies on. Cases opened before
this release need a one-off backfill in a real deployment; local and test data start empty.

## Code check (2026-11-23, Day 25 B, M16)

Matches the code: own `aml` schema and Flyway, `processed_events` claim, 3 attempts then `<topic>.DLT`,
`FreezeRequestRelay` and `CaseEventRelay` outboxes, the case workflow with `NOT_CASE_ASSIGNEE`, the STR CSV.
`GET /aml/alerts` and `POST /aml/cases/{id}/ai-summary` (optional M15) are **not built** and are refused by the
deny-all rule (`AmlSecurityIT`). **Still open** from "M12": no alert on DLT traffic or on freeze requests stuck in
the outbox.

## Update (2026-11-23, Day 26 B)

`GET /aml/alerts` is built: `AmlAlertController` → `AmlAlertService` → `AlertRepository.search`, with optional
`ruleCode`, `severity` and `walletId` filters, newest first. The query is a Spring Data fragment (`AlertSearchImpl`,
Criteria API) and not `JpaSpecificationExecutor`, which would add a `delete(Specification)` to an append-only table.
It has its own security rule, and `AmlSecurityIT` and `AmlEndpointAuthorizationCoverageIT` have rows for it.
`POST /aml/cases/{id}/ai-summary` stays deferred (M15). The DLT and stuck-outbox alerts are still open.
