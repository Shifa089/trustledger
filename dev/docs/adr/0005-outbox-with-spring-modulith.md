# ADR 0005: Transactional outbox with Spring Modulith's JDBC event publication registry

- Status: Accepted (part A: the outbox table and event identity; part B: Kafka externalization, republishing,
  cleanup)
- Date: 2026-11-21
- Spec: docs/SPEC.md §4 "Transfer flow" steps 7–8 and `event_publication`, §6 "Events" / "Kafka", §8 M8

## Context

A completed transfer must reach the AML service (Kafka) and notifications. The naive version is a **dual write**:
commit the transfer to PostgreSQL, then send to Kafka. The two can't be atomic:

- commit succeeds, the send fails (Kafka down, process killed between the two): the money moved and AML never
  hears about it. For a regulated wallet, that's a hole in transaction monitoring;
- send first, then the commit fails: AML sees a transfer that never happened.

Sending inside the transaction doesn't help (Kafka isn't part of the database transaction) and would also hold the
wallet row locks during a remote call, which CLAUDE.md forbids.

## Options

1. **Send to Kafka after commit, best effort.** Simple, loses events on any crash or broker outage.
2. **Hand-written outbox table** written in the money transaction, plus a poller that sends and marks rows. Works,
   but we'd write and test the poller, retries, ordering and cleanup ourselves.
3. **Spring Modulith event publication registry (JDBC)**: every published event that has a listener is stored in
   `event_publication` in the publishing transaction; it is marked completed when the listener (later: the Kafka
   externalizer) succeeds; incomplete ones can be resubmitted. Already in the stack (Spring Modulith, same BOM).
4. **Change data capture (Debezium)** on the outbox table: robust, but a Kafka Connect cluster is far too much
   infrastructure for this project.

## Decision

Option 3, with `spring-modulith-events-jdbc` plus `spring-modulith-events-jackson` (the `EventSerializer` the JDBC
registry needs to store events as JSON). Both are managed by the Spring Modulith BOM; no new vendor.

**The table is created by Flyway** (`V16__shared_create_event_publication.sql`, in the `shared` schema next to
ShedLock), not by Modulith's schema initialisation (`spring.modulith.events.jdbc.schema-initialization.enabled=false`,
`spring.modulith.events.jdbc.schema=shared`):

- every other table is Flyway's; one owner keeps the schema history complete;
- Modulith's script is `CREATE TABLE IF NOT EXISTS`: after an upgrade that changes the layout (as 1.x → 2.x did),
  it would silently keep the old table. With Flyway, an upgrade needs a reviewed migration;
- we choose schema and indexes ourselves.

The cost: the DDL must match the Modulith version. V16 is copied from the 2.1.2 jar
(`schemas/v2/schema-postgresql.sql`), and `EventPublicationRegistryIT` writes and reads through the real registry,
so a mismatch fails the build. `ddl-auto=validate` doesn't cover it (not a JPA table).

**Event identity is fixed at publish time.** Modulith stores the *internal* event and, on resubmission, delivers the
stored one again; the Kafka mapping (`TransferCompleted.toV1()`, ADR 0016 addendum) runs again too. So `eventId`
can't be generated during mapping, or a re-sent message would look new to the consumer's de-duplication. Each
internal event carries `EventMetadata(eventId UUIDv7, correlationId)`, created by `EventMetadata.capture(clock)` in
the publishing service and serialized with the event.

## Consequences

- A committed money movement always has its publication row; a rolled-back one never has (tested).
- **Module listeners are asynchronous now.** With the registry present, Spring Modulith enables `@EnableAsync`, so
  `@ApplicationModuleListener` runs on a separate (virtual) thread after the commit. ADR 0012's "runs on the
  publishing thread" no longer holds. A failing listener no longer loses the event: its publication stays
  incomplete (status `FAILED`) and can be resubmitted. Tests that observe a listener's effect wait for it
  (`Eventually`).
- Delivery is **at least once**: a resubmission can deliver an event a second time. Consumers must be idempotent on
  `eventId` (SPEC §6 "Kafka"; AML's `processed_events`, M9).
- The table grows with every event; part B deletes completed rows after 7 days (below).

## Part B: externalization to Kafka

Added `spring-modulith-events-kafka` (Modulith BOM). The Kafka externalizer is one more listener of the outbox:
every externalized event gets its own `event_publication` row, completed when the broker acknowledges the send.

**Only the contract leaves core-app.** A bare `@Externalized` would send the *internal* event, which can hold data
that must not leave (`WalletFrozen.userId`, `journalEntryId`). So:

- the internal event carries the routing: `@Externalized(TransferCompletedV1.TOPIC + "::#{#this.senderWalletId()}")`
  (topic from the contract, key = the wallet ID; for a transfer the sender, so one sender's transfers stay in order
  on one partition). Modulith evaluates it on the internal event, before mapping;
- the event implements `shared.ExternalizedEvent` (`toV1()`, `metadata()`), and one `EventExternalizationConfiguration`
  (`shared.events.EventExternalizationConfig`) selects `@Externalized` + `ExternalizedEvent` and maps to the contract.
  It knows no business module, so `shared` stays free of dependencies on them (Modularity test). An ArchUnit rule
  checks that every `@Externalized` type is an `ExternalizedEvent` record, so none goes out unmapped or is silently
  skipped;
- **headers** `eventId` and `correlationId` (UTF-8 bytes, `correlationId` left out when there is none, e.g. a
  scheduled job). Modulith computes configured headers from the *mapped* object, which has no correlation ID, so the
  mapping returns a Spring `Message` (contract + headers); the Kafka transport sends a `Message` as it is;
- **serialization**: Modulith's `ByteArrayJacksonJsonMessageConverter` writes the contract as JSON bytes with Boot's
  `JsonMapper`, so the producer uses `ByteArraySerializer` (a JSON serializer would encode the bytes again, as a
  base64 string; `KafkaExternalizationIT` caught exactly that). The `__TypeId__` header names the contract record.
- Producer: `acks=all`, idempotence on, `delivery.timeout.ms` bounded so a broker outage fails the send (the
  publication becomes `FAILED`) instead of blocking.

core-app produces three of the four contracts (`TransferCompletedV1`, `TopupCapturedV1`, `WalletFrozenV1`).
`AccountFreezeRequestedV1` is produced by risk-aml-service (M9) through an outbox of its own: a small hand-written
table and relay rather than Modulith's registry (ADR 0007, amendment of 2026-11-22).

**Topics.** `NewTopic` beans for all four contract topics (3 partitions, replication 1), only where
`trustledger.events.topics.create=true`: the local profile and tests. In AWS, topics belong to the infrastructure.

**Republish on startup:** `spring.modulith.events.republish-outstanding-events-on-restart=true`. Incomplete
publications (crash before the send, broker down) are sent again when an instance starts, with the stored event, so
with the same `eventId`. With several instances each one republishes what it finds, and an event can also be in
flight elsewhere: more duplicates, all de-duplicated on `eventId` by the consumers. Republishing while running
(e.g. a scheduled resubmission of `FAILED` rows, or the staleness monitor) is not configured yet; today a failed send
waits for the next restart. That is acceptable for M8 and belongs with alerting in M12 (Observability).

**Cleanup:** `EventPublicationCleanupJob` (nightly, ShedLock) calls `CompletedEventPublications.deletePublicationsOlderThan(7 days)`
(`trustledger.events.retention`, at least 1 day). Only completed rows are deleted; incomplete and failed rows stay
whatever their age, because republishing needs them. Completion mode stays `UPDATE` (rows are marked, then deleted
by the job) so a recent send can still be looked up when a consumer reports a problem.

**Tests** (Kafka Testcontainer, the compose image `apache/kafka:4.4.0`, via `testcontainers-kafka` from the
Testcontainers BOM): exactly one record per event, keyed by wallet, contract JSON, headers, no internal fields;
nothing for a rolled-back transfer; the broker paused during a transfer, then the restart hook sends the stored event
with the same `eventId`; topics created; cleanup deletes only old completed rows.
