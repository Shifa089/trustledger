# ADR 0005: Transactional outbox with Spring Modulith's JDBC event publication registry

- Status: Accepted (part A: the outbox table and event identity; part B adds Kafka externalization)
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
- The table grows with every event. Part B configures republishing on restart and deletes completed publications
  after 7 days (the `completion_date` index serves both).
- Part B adds `spring-modulith-events-kafka` and the routing (topic from the contract's `TOPIC`, key = `walletId`,
  `eventId` and `correlationId` headers from `EventMetadata`).
