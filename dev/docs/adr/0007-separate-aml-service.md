# ADR 0007: AML as a separate service with idempotent Kafka consumers

- Status: Accepted
- Date: 2026-11-21
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
