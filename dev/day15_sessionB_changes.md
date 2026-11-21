# Changes

## DAY15 Session B (M8 part B): outbox to Kafka, topics, republishing on restart, cleanup after 7 days

**Goal:** the second half of M8 (SPEC §8): send the outbox events to Kafka with `spring-modulith-events-kafka`
(`@Externalized`, topic names from §6, wallet ID as key, `eventId` and `correlationId` headers), create the topics
locally and in tests, republish incomplete publications on startup, delete completed ones after 7 days, and extend
ADR 0005.

Branch: `feature/day15-kafka-externalization` (created from `master`), merged into `master`.

Decisions approved in the plan:
- **core-app externalizes three events** (`TransferCompleted`, `TopupCaptured`, `WalletFrozen`). The fourth
  contract, `AccountFreezeRequestedV1`, is produced by risk-aml-service (M9); core-app only creates its topic.
- **`@Externalized` routes, a mapping sends the contract.** `@Externalized` alone would send the internal event
  (with `userId`, `journalEntryId`), so the payload is always `toV1()`.
- The mapping lives in `shared`, behind a small `ExternalizedEvent` interface, so `shared` never depends on a
  business module.
- New test dependency `testcontainers-kafka` (Testcontainers BOM, already in the stack).
- Topic creation is switched on by a property (`trustledger.events.topics.create`), set in the local profile and
  the test config.

---

### 1. Externalization (`spring-modulith-events-kafka`)

- `core-app/pom.xml`: `spring-modulith-events-kafka` (Modulith BOM); `testcontainers-kafka` (test).
- `shared/ExternalizedEvent` (new): `Record toV1()` and `EventMetadata metadata()`.
- `TransferCompleted`, `TopupCaptured`, `WalletFrozen` implement it and carry the routing:
  - `@Externalized(TransferCompletedV1.TOPIC + "::#{#this.senderWalletId()}")`: the sender is the key, so one
    sender's transfers stay in order on one partition (AML's rules count a sender's transfers);
  - `@Externalized(TopupCapturedV1.TOPIC + "::#{#this.walletId()}")`;
  - `@Externalized(WalletFrozenV1.TOPIC + "::#{#this.walletId()}")`.
  Topic names come from the contracts' `TOPIC` constants, so §6's names live in one place.
- `shared/events/EventExternalizationConfig` (new): one `EventExternalizationConfiguration` that selects
  `@Externalized` **and** `ExternalizedEvent`, and maps each event to a Spring `Message` holding the contract plus the
  headers `eventId` and `correlationId`. Header values are UTF-8 bytes. `correlationId` is left out when there is
  none, e.g. for an event published by a scheduled job.
  - **Why a `Message` and not `.headers(...)`:** I read the Modulith 2.1.2 bytecode. It computes configured headers
    from the *mapped* object (the contract), which has no correlation ID. A mapped `Message` is sent as it is,
    headers included. Routing, on the other hand, is evaluated on the *internal* event, before mapping, which is why
    `#this.senderWalletId()` works.
- Producer (`application.yml`):
  - `StringSerializer` for keys and **`ByteArraySerializer`** for values;
  - `acks=all` and `enable.idempotence=true`;
  - bounded `max.block.ms`, `request.timeout.ms` and `delivery.timeout.ms`, so a broker outage fails the send
    (publication `FAILED`) instead of hanging;
  - `logging.level.org.apache.kafka: WARN`, because Kafka clients dump their whole configuration at INFO.
  - Local profile: `spring.kafka.bootstrap-servers: localhost:9092` (the compose broker).

### 2. Topics (`NewTopic` beans, local and test)

- `shared/events/KafkaTopicsConfig` (new): a `KafkaAdmin.NewTopics` bean for all four contract topics, with 3
  partitions and replication 1 (`trustledger.events.topics.*`). It only exists when
  `trustledger.events.topics.create=true`, which `application-local.yml` and `src/test/resources/config/application.yml`
  set. In AWS, topics belong to the infrastructure.
- `KafkaTopicsConfig.TOPICS` is the single list of contract topics (tests use it too).

### 3. Republish on startup, cleanup after 7 days

- `spring.modulith.events.republish-outstanding-events-on-restart: true`. Modulith's startup hook
  (`PersistentApplicationEventMulticaster.afterSingletonsInstantiated()`) resends every incomplete publication from
  the stored event, so with the same `eventId`.
- `shared/events/EventsProperties` (new, `trustledger.events.*`):
  - `retention` = 7d, minimum 1d;
  - `cleanup-cron` = 03:30 nightly;
  - `lock-at-most-for`;
  - `topics` (`create`, `partitions`, `replicas`).
  Startup fails on unsafe values.
- `shared/events/EventPublicationCleanupJob` (new): `@Scheduled` plus ShedLock through `LockingTaskExecutor` (same
  pattern as `IdempotencyCleanupJob`, lock `shared-event-publication-cleanup`). It calls
  `CompletedEventPublications.deletePublicationsOlderThan(retention)`. Only completed rows are deleted; incomplete and
  failed ones stay whatever their age, because republishing needs them.

### 4. Tests

**Unit and architecture:**
- `EventExternalizationConfigTest` (7):
  - the three events are selected; topic and key are right (the sender for transfers);
  - the payload equals `toV1()`, and `WalletFrozenV1` carries no user ID;
  - headers hold the eventId and correlationId as UTF-8, with no correlation header outside a request;
  - mapping is stable for a resubmitted event;
  - an `ExternalizedEvent` without `@Externalized`, and an `@Externalized` type without a contract, are not sent.
- `EventsPropertiesTest` (7): shipped values accepted; retention below 1 day, a bad cron, a zero lock, and zero
  partitions or replicas are rejected; topics default to not created.
- `ArchitectureRulesTest` + `externalizedEventsMapToAContract`: every `@Externalized` type is an `ExternalizedEvent`
  record, so no internal event can go out unmapped or be silently skipped.

**Integration (Testcontainers Kafka `apache/kafka:4.4.0`, same image as compose, added to `TestcontainersConfiguration`,
so every IT now runs against a real broker):**
- `KafkaTestConsumer` (new helper): a plain Kafka client (no Spring), as AML will be. It assigns partitions
  directly, reads from the beginning, and keeps polling briefly after the expected count so a duplicate would show up.
- `KafkaExternalizationIT` (4):
  - a transfer (correlation ID in the MDC) arrives **exactly once**, keyed by the sender wallet. The JSON reads back
    into the same `TransferCompletedV1` as `toV1()`, the `eventId` and `correlationId` headers are right,
    `__TypeId__` names the contract, and the raw JSON has no `journalEntryId`, `senderWalletId`, `metadata` or
    `userId`. The Kafka outbox row is completed;
  - a rolled-back transfer sends nothing;
  - a captured top-up arrives keyed by wallet, with no correlation header (no request);
  - a frozen wallet arrives without the user ID or the officer's reason.
- `KafkaRepublishOnRestartIT` (1): **the "survives a restart if Kafka was down" criterion.** It runs in its own
  context with short producer timeouts.
  1. The Kafka container is paused (`docker pause`), then the transfer commits: the money moves and the Kafka row
     becomes `FAILED` with no completion date.
  2. Kafka is unpaused, then the startup hook runs again, which is what a restarted instance does first.
  3. Exactly one record arrives with the **same `eventId`** and the same contract, and the row is completed.
- `KafkaTopicsIT` (1): all four topics exist with 3 partitions and 1 replica.
- `EventPublicationCleanupJobIT` (2): only the completed row from 8 days ago is deleted. The completed row from 6
  days ago, an old `FAILED` row and an old `PUBLISHED` row stay. When another instance holds the ShedLock lock, the
  job skips.

**Changed:** `EventPublicationRegistryIT` counts only the test recorder's rows. The Kafka externalizer now adds its
own row per `TransferCompleted`.

### 5. Docs

- `docs/adr/0005-outbox-with-spring-modulith.md`: status now covers parts A and B. The new "Part B" section covers:
  - why bare `@Externalized` isn't enough;
  - routing vs. mapping, and why headers travel in a `Message`;
  - `ByteArraySerializer`;
  - topics;
  - restart republishing (duplicates with several instances; no resubmission while running yet, which belongs with
    alerting in M12);
  - the cleanup job and its retention;
  - the tests.
- `docs/SPEC.md` §6 "Events" row: the real `@Externalized` expression and the mapping to the contract.

### 6. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS**. event-contracts: 31 unit tests. core-app: 1054 unit tests
(Surefire, +15) and 606 integration tests (Failsafe, +8), every IT now with a real Kafka broker. risk-aml-service: 1.
0 failures, 0 errors. `ModularityTests` and `ArchitectureRulesTest` (with the new rule) green.

### Still open in M8

The `notification` module (Mailpit emails for FR-N-1) is part of M8 in the SPEC but was not in this session's scope.

### What to learn from this

1. **Separate routing from payload.** The topic and key are about the *internal* event (which wallet, which stream).
   The payload is the *contract*. Keeping them apart is what lets the internal event hold more data than may ever
   leave the service.
2. **Read the library, don't guess.** Two details decided the design, and both came from Modulith's bytecode, not
   from docs. Routing is resolved before mapping (so `#this.senderWalletId()` works). Headers are resolved after
   mapping (so the correlation ID would have been lost).
3. **Test the bytes on the wire, with a plain client.** The first run sent the contract as a JSON *string of
   base64*: Modulith's message converter had already made JSON bytes, and a JSON serializer encoded them again.
   Unit tests on the mapping were all green. Only reading the real topic like a foreign consumer showed it.
4. **"Survives a restart" needs a real failure, not a mock.** Pausing the broker container makes the real producer
   time out, and the real registry marks the row `FAILED`. The test then proves the property that matters: same
   `eventId` after resend, so consumers can de-duplicate.
5. **Housekeeping must respect the recovery path.** The cleanup deletes only *completed* rows. An old `FAILED` row
   is exactly what republishing needs, so deleting rows by age alone would silently lose events.
