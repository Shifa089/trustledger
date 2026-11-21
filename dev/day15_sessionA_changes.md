# Changes

## DAY15 Session A (M8 part A): the outbox table, Kafka event contracts, and mapping internal events to them

**Goal:** the first half of M8 (SPEC §8): Spring Modulith's JDBC event publication registry as the transactional
outbox, the four versioned Kafka contracts in `event-contracts`, and the mapping from core-app's internal events to
those contracts. No Kafka sending yet: `spring-modulith-events-kafka`, `@Externalized`/routing, headers, republishing
on restart and cleanup after 7 days come in Session B.

Branch: `feature/day15-event-contracts` (created from `master`), merged into `master`.

Decisions approved in the plan:
- contract amounts are `long amountMinor` + `String currency` (ADR 0003), not decimal strings: machine-to-machine,
  and AML stores `amount_minor bigint`;
- internal events carry `EventMetadata` (eventId + correlationId), so the ID stays the same when an event is resubmitted;
- ADR 0005 is written now, since the registry is the outbox; Session B adds the Kafka part.

---

### 1. Outbox: `spring-modulith-events-jdbc`, table created by Flyway

| File | Change |
|---|---|
| `core-app/pom.xml` | + `spring-modulith-events-jdbc` and `spring-modulith-events-jackson` (the JDBC registry needs a JSON `EventSerializer`, which only the Jackson artifact provides). Versions from the Spring Modulith BOM 2.1.2; no new vendor. |
| `core-app/src/main/resources/db/migration/V16__shared_create_event_publication.sql` | New. `shared.event_publication`, copied from the jar's `schemas/v2/schema-postgresql.sql` (2.1.2): `id`, `listener_id`, `event_type`, `serialized_event`, `publication_date`, `completion_date`, `status`, `completion_attempts`, `last_resubmission_date`, plus Modulith's hash index on `serialized_event` and the `completion_date` index. |
| `core-app/src/main/resources/application.yml` | `spring.modulith.events.jdbc.schema: shared`, `schema-initialization.enabled: false` (explicit; it's the default). |

**Why Flyway and not Modulith's own schema initialisation:**
1. Every other table belongs to Flyway, and one owner keeps the schema history complete.
2. Modulith's script is `CREATE TABLE IF NOT EXISTS`. The table layout changed between Modulith 1.x and 2.x. On an
   existing database the script would silently keep the **old** table. With Flyway, an upgrade needs a reviewed
   migration.
3. We choose the schema (`shared`, next to ShedLock) and the indexes.

The cost: V16 has to match the Modulith version, and `ddl-auto=validate` doesn't check it because it isn't a JPA
table. `EventPublicationRegistryIT` writes and reads through the real registry, so a mismatch fails the build.

**Side effect:** with the registry on the classpath, Spring Modulith turns on `@EnableAsync`. Every
`@ApplicationModuleListener` (for example the wallet module's `KycLevelUpgraded` → open wallet, and `WalletCreated` →
link the ledger account) now runs **on a separate virtual thread after the commit**, not on the publishing thread
as in ADR 0012. A failing listener no longer loses the event: its publication stays incomplete (status `FAILED`)
and can be resubmitted. ADR 0012 has an update note. `WalletCreationScenarioIT` had to wait for the listeners
(section 4).

### 2. `event-contracts`: four versioned records

All plain Java, no compile dependency. Each has `eventId`, `occurredAt` and `walletId` (the Kafka key) first, checks
its fields in the compact constructor (a producer bug fails on the producer side), and has a `TOPIC` constant.

| Record | Fields | Topic |
|---|---|---|
| `TransferCompletedV1` | eventId, occurredAt, walletId (**sender**), counterpartyWalletId (receiver), transferId, amountMinor, currency | `trustledger.payments.transfer-completed.v1` |
| `TopupCapturedV1` | eventId, occurredAt, walletId, topupId, amountMinor, currency | `trustledger.payments.topup-captured.v1` |
| `WalletFrozenV1` | eventId, occurredAt, walletId, source (`"COMPLIANCE"`/`"AML"`) | `trustledger.wallet.wallet-frozen.v1` |
| `AccountFreezeRequestedV1` | eventId, occurredAt, walletId, caseId, ruleCode | `trustledger.aml.account-freeze-requested.v1` |

**Left out on purpose:**
- `userId`: no consumer needs it, and it links back to a person.
- `journalEntryId`: internal ledger detail.
- The transfer note and the freeze reason: free text that may quote personal data.
- Alert details.

`source` is a String, so an AML consumer built against V1 still reads a value added later (tolerant reader).
`Checks` (package-private) holds the shared field checks.

### 3. Mapping internal events to contracts (core-app)

| File | Change |
|---|---|
| `shared/EventMetadata.java` | New record `(UUID eventId, String correlationId)`. `EventMetadata.capture(clock)` = `Ids.newV7(clock)` + correlation ID from the MDC (null outside a request). |
| `payments/TransferCompleted.java` | + `EventMetadata metadata`; + `toV1()` (sender → `walletId`, receiver → `counterpartyWalletId`, `Money` → minor units + ISO code). |
| `payments/TopupCaptured.java` | + `metadata`; + `toV1()`. |
| `wallet/WalletFrozen.java` | + `Instant frozenAt` (it had no timestamp), + `metadata`; + `toV1()` (drops `userId`; `source.name()`). |
| `TransferService`, `HeldTransferService`, `TopupSettlementService`, `WalletComplianceService` | pass `EventMetadata.capture(clock)` (and the freeze time) when publishing. |

**Why the event ID lives in the internal event:** Modulith stores the internal event and, after a crash or a failed
send, resubmits the **stored** event, so the mapping runs again. An ID generated in the mapper would be different
each time, and AML's de-duplication would accept a re-sent message as a new one. `toV1()` is pure: mapping the same
stored event twice gives equal contracts (tested).

**Why `toV1()` sits on the source record (ADR 0016 addendum):** the contract jar can't see core-app types, so
"static `of(...)` on the target" isn't possible. A method on the module's public event keeps each module in charge
of what it exposes, and Session B's externalization config can call it without reaching into a module's internals.

The correlation ID is in `EventMetadata` but **not** in the contract payload. Session B sends it as a Kafka header.

### 4. Tests

**event-contracts (new: JUnit + AssertJ, test scope only; both already in the stack):**
- `ContractRecordsTest` (19): valid records keep their fields, topics are spelled right; nulls, non-positive amounts,
  bad currencies (`inr`, `IN`, `1NR`, …), blank source/ruleCode and sender = receiver are rejected.
- `ContractPrivacyTest` (12): every field is on an allowlist; no field name looks like personal data or free text
  (`user|name|email|phone|pan|dob|address|note|reason|detail|ip|…`); every contract starts with
  `eventId, occurredAt, walletId`. Adding a field forces you to change this test.

**core-app unit:**
- `EventMetadataTest` (5): v7 ID taken from the clock, a new ID each time, correlation ID from the MDC or null.
- `TransferCompletedTest`, `TopupCapturedTest`, `WalletFrozenTest`: exact mapping, mapping twice gives equal records,
  user ID not in `WalletFrozenV1`, metadata/frozenAt required.
- `EventContractJsonTest` (8): each contract serialized with Jackson 3 equals a fixed JSON (field names and types
  locked), and that JSON reads back into an equal record (as risk-aml-service will).
- Updated: `TransferEventsTest`, `HeldTransferServiceTest`, `TopupSettlementServiceTest`,
  `WalletComplianceServiceTest` capture the published event and compare it ignoring `metadata` (its ID is random),
  then assert the metadata is present.

**core-app integration (Testcontainers PostgreSQL):** `EventPublicationRegistryIT`, with `TransferCompletedRecorder`
(a test-only `@ApplicationModuleListener` that records deliveries and can fail for chosen sender wallets):
1. a completed transfer → exactly one publication row; the stored JSON has the eventId and both wallets; it is
   completed once the listener succeeds;
2. the transfer published its event and then the outer transaction rolled back → **no** transfer and **no**
   publication (no dual write);
3. the listener fails → the transfer stays committed and the row is `FAILED` with no `completion_date`;
   `IncompleteEventPublications.resubmitIncompletePublications(...)` delivers the event read back from JSON, **equal**
   to the first (same eventId, same `toV1()`), and the row is completed.

**Changed because listeners are async now:**
- `Eventually` (new, test sources): retries an assertion until a timeout. It's a small helper, so no Awaitility
  dependency was added.
- `WalletCreationScenarioIT`: waits until no publication mentioning the user is incomplete. That condition holds
  only once the whole chain (`KycLevelUpgraded` → `WalletCreated` → ledger link) has finished, because the
  `WalletCreated` row is committed before `KycLevelUpgraded` is marked done.
  - It counts `WalletCreated` publications instead of `@RecordApplicationEvents`, which only sees events on the
    test thread.
  - The rollback test now also proves there is no publication row, so it doesn't pass just because the check ran
    before an async listener could.
- `WalletControllerIT`: field-by-field check of `WalletFrozen` (frozenAt, metadata).

### 5. Docs

- `docs/adr/0005-outbox-with-spring-modulith.md` (new): the dual-write problem, the options (best effort, our own
  outbox, Modulith registry, Debezium), the choice, Flyway ownership, event identity, and the consequences
  (async listeners, at-least-once delivery, cleanup in part B).
- `docs/adr/0016-…`: addendum: Kafka contracts are mapped on the source record (`toV1()`).
- `docs/adr/0012-…`: update note (events-jdbc instead of starter-jpa; listeners async now).
- `docs/SPEC.md` §4: `shared.event_publication` row with its columns, created by Flyway (V16); `shared` schema
  sentence.

### 6. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS**. event-contracts: 31 unit tests. core-app: 1039 unit tests (Surefire) and 598 integration tests (Failsafe, incl. the 3 new in `EventPublicationRegistryIT`). risk-aml-service: 1. 0 failures, 0 errors.

`ModularityTests` and `ArchitectureRulesTest` green. The ERROR logs from `SimpleAsyncUncaughtExceptionHandler`
("Simulated listener failure…") come from `EventPublicationRegistryIT` test 3; they're expected.

### What to learn from this

1. **The outbox is about atomicity, not about Kafka.** The guarantee comes from writing the event row in the
   same database transaction as the money. Everything after that (sending, retrying) can fail and be retried,
   because the row is the durable record of what still has to be delivered.
2. **At-least-once delivery means the ID must be fixed before the first attempt.** If a retry can produce a
   different `eventId`, consumer de-duplication can't work. Put identity into the stored event, not into the
   mapping that runs on every attempt.
3. **A contract is a public API.** Lock its JSON shape with a test, keep it small ("only what consumers need"),
   version it by name (`V1`, a new topic for a breaking change), and guard it against personal data with an
   allowlist test rather than relying on reviewers.
4. **Adding infrastructure can change behaviour elsewhere.** The registry silently made every module listener
   asynchronous. The tests that broke were relying on timing, not on the system's real guarantees. Wait on a real
   signal (here: the outbox row completing), not on `sleep`.
5. **Own your schema.** Library-managed `CREATE TABLE IF NOT EXISTS` is convenient on day 1 and a silent drift risk on
   the first upgrade. A copied DDL plus an integration test that goes through the library catches drift.
