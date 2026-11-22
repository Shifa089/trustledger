# Changes

## DAY19 (M10, FR-A-1): hash-chained audit trail

**Goal:** start M10 in `core-app` (SPEC FR-A-1 §2, `audit.audit_events` §4, §8 M10):
1. the `audit.audit_events` migration with an append-only trigger;
2. an `AuditWriter` that, in the caller's transaction, takes `pg_advisory_xact_lock(<constant>)`, reads the latest
   hash, computes `hash = SHA-256(prev_hash + canonical JSON with sorted keys)` and inserts; the first event uses a
   genesis hash;
3. listeners for every public domain event, writing audit events with masked before/after.

Branch: `feature/day19-audit-hash-chain` (created from `master`), merged into `master`.

Decisions (taken as "best for the project"):
- **Synchronous listeners in the publishing transaction**: `@TransactionalEventListener(phase = BEFORE_COMMIT)`,
  `@Order(LOWEST_PRECEDENCE)`, not `@ApplicationModuleListener`. The audit row and the business change commit or
  roll back together; actor, IP and correlation ID are still on the thread; nothing is redelivered, so no dedup key.
  Cost: the chain lock is held until the business commit, so audited transactions commit one at a time.
- **ADR 0008 written today**, because the listener decision belongs in it.
- **`JdbcClient` port instead of a JPA entity** for `audit_events`, like the other append-only tables
  (`processed_events`, `webhook_events`): rows are only inserted, and `before`/`after` must be stored exactly as
  the canonical JSON that was hashed.
- **`seq` is not hashed**; `prev_hash` fixes the position, and a rolled-back writer leaves a harmless gap.

No new dependency.

---

### 1. Migration `V19__audit_create_audit_events.sql`

| Column | Notes |
|---|---|
| `seq` | `bigserial` primary key, the chain order |
| `occurred_at` | `timestamptz`; the hashed value is cut to microseconds so it round-trips exactly |
| `actor_id` | `uuid`, our user ID; NULL only for SYSTEM (`ck_audit_events_actor`) |
| `actor_role` | CUSTOMER, COMPLIANCE_OFFICER, ADMIN, AUDITOR or SYSTEM |
| `action`, `entity_type` | upper-case codes; `entity_id` `uuid` |
| `before`, `after` | `jsonb`, masked |
| `ip` | `varchar(64)`, not `inet` (inet would rewrite IPv6 text and break the hash) |
| `correlation_id` | `varchar(64)` |
| `prev_hash`, `hash` | `char(64)`, lower-case hex checks |
| `created_at` | |

- `hash` UNIQUE and **`prev_hash` UNIQUE**: the database refuses a fork (two rows extending the same
  predecessor), and genesis can start the chain only once.
- Triggers refuse UPDATE, DELETE (per row) and TRUNCATE (per statement), `audit.reject_audit_mutation()`, like V8.
- Index `(entity_type, entity_id, seq)` for `GET /audit/events` (Day 20).

### 2. Domain (`com.trustledger.core.audit.domain`)

- **`CanonicalJson`**: a hand-written canonical writer: keys sorted at every depth, no whitespace, nulls written,
  RFC 8259 escaping. Only strings, booleans, integral numbers, maps, lists and null; fractions are refused.
  Hand-written so the hash format can't change when a JSON library does.
- **`AuditEvent`**: the hashed content (everything but `seq`, hashes and `created_at`); `canonicalJson()`,
  `beforeJson()`/`afterJson()`. Cuts `occurredAt` to micros, formats it with six fraction digits, copies and
  validates before/after, drops an IP longer than 64 characters instead of failing the business change.
- **`AuditHashing`**: `GENESIS_HASH` (64 zeros) and `hash(prevHash, event) = SHA-256(prevHash + canonicalJson)`.
- **`AuditMasker`**: masks values under personal keys (pan, phone/mobile, email, name/fullName, dob, address; case
  and separators ignored) at any depth with the shared `Masking` helpers; a structure under such a key is fully
  masked, a list is masked item by item.
- **`AuditActor`** (most privileged role wins: ADMIN > COMPLIANCE_OFFICER > AUDITOR > CUSTOMER; no token = SYSTEM),
  **`AuditContext`** (actor, IP, correlation ID; the event's correlation ID wins), **`AuditEntry`**,
  **`AuditAction`**, **`AuditEntityType`**, **`ChainedAuditEvent`**.
- Port **`AuditEventStore`**: `lockChain()`, `latestHash()`, `append(...)`.

### 3. Application (`com.trustledger.core.audit.application`)

- **`AuditWriter.write(entry, context)`**, `@Transactional(propagation = MANDATORY)`: mask, build the event
  (validated **before** the lock, so a bad entry doesn't make others wait), lock, read the latest hash or genesis,
  hash, insert. The javadoc explains why it needs READ COMMITTED.
- **`AuditListeners`**: one BEFORE_COMMIT handler per public event; **`AuditEntries`** maps each one:

| Event | Action | Entity | Before → after |
|---|---|---|---|
| `KycLevelUpgraded` | `KYC_LEVEL_UPGRADED` | USER | → `{kycLevel}` |
| `WalletCreated` | `WALLET_CREATED` | WALLET | → `{userId, status: ACTIVE}` |
| `WalletFrozen` | `WALLET_FROZEN` | WALLET | `{status: ACTIVE}` → `{status: FROZEN, freezeSource}` |
| `WalletUnfrozen` | `WALLET_UNFROZEN` | WALLET | `{status: FROZEN}` → `{status: ACTIVE}` |
| `TopupCaptured` | `TOPUP_CAPTURED` | TOPUP | → `{status, walletId, amount, currency, journalEntryId}` |
| `TransferCompleted` | `TRANSFER_COMPLETED` | TRANSFER | → `{status, senderWalletId, receiverWalletId, amount, currency, journalEntryId}` |

  Amounts are decimal strings. Only what the event proves goes in (no guessed "before" for a transfer, which may
  have been HELD_FOR_REVIEW). The officer's free-text freeze reason is not in any event, so it never reaches the
  trail.
- Port **`AuditContextProvider`**.

### 4. Infrastructure (`com.trustledger.core.audit.infrastructure`)

- **`JdbcAuditEventStore`**: `SELECT pg_advisory_xact_lock(0x544C5F4155444954)` ("TL_AUDIT");
  `SELECT hash ... ORDER BY seq DESC LIMIT 1`; `INSERT ... cast(:before as jsonb) ... RETURNING seq`. Joins the
  JPA transaction.
- **`RequestAuditContextProvider`**: `JwtAuthenticationToken` subject → `IdentityApi.findUserId`, authorities →
  role; `ServletRequestAttributes.getRemoteAddr()`; MDC `correlationId`.

Module dependencies: `audit` → kyc, wallet, payments, identity (public types only); nothing depends on `audit`.
`ModularityTests` and `ArchitectureRulesTest` are green.

### 5. Tests

**Unit (53 new):**
- `CanonicalJsonTest` (8): sorted keys at depth, insertion order irrelevant, explicit nulls, Integer = Long,
  escaping, non-ASCII, fractions and non-string keys refused.
- `AuditHashingTest` (7): pinned canonical JSON; **a test vector computed outside Java** (`sha256sum`);
  genesis; deterministic; depends on `prevHash`; **every one of the 10 hashed fields changes the hash**; malformed
  `prevHash` refused.
- `AuditEventTest` (7): micros truncation, fixed six fraction digits, overlong IP dropped, codes validated,
  unsupported values fail early, state copied, `of(...)` takes the context.
- `AuditMaskerTest` (7), `AuditActorTest` (5), `AuditEntriesTest` (6, one per event).
- `AuditWriterTest` (5): genesis; links to latest; **InOrder lock → read → insert**; stores masked event with
  clock time; a bad entry fails before the lock.
- `AuditListenersTest` (4): correlation precedence; **every handler is BEFORE_COMMIT, no fallback, lowest
  precedence** (reflection).
- `RequestAuditContextProviderTest` (4): officer in a request; nobody = SYSTEM; non-JWT = SYSTEM; unknown subject
  = SYSTEM.

**Integration (22 new, Testcontainers PostgreSQL):**
- `AuditMigrationIT` (7): UPDATE, DELETE, TRUNCATE refused; fork (duplicate `prev_hash`) and duplicate hash
  refused; hash format; unknown role; anonymous non-system actor.
- `AuditWriterIT` (7): links; two events in one transaction; **stored rows re-hash to their stored hash** (quotes,
  backslashes, ₹ and Devanagari through `jsonb`); chain starts at genesis; no transaction → refused; rolled-back
  caller leaves no row and the chain still links; **16 threads × 10 transactions → one linear chain containing all
  160 events**. Every test that checks the chain checks the whole table (`AuditRows.assertChainIntact`).
- `AuditTrailIT` (8): **an officer freezes and another unfreezes over HTTP** → rows with their user IDs, role,
  IP (IPv4 and IPv6), `X-Correlation-Id`, before/after; the reason (which quotes a PAN) is nowhere in the trail;
  wallet creation and an **AML freeze are SYSTEM**; KYC upgrade, captured top-up and completed transfer rows with
  the event's correlation ID; **a rolled-back transaction and a refused second freeze leave no row**.

**Mutation check:** with `lockChain()` turned into a no-op, `concurrentWritersKeepOneLinearChain` failed
(`duplicate key value violates unique constraint "uk_audit_events_prev_hash"`): without the lock, writers read
the same last hash, and the constraint refuses the fork. Lock restored.

The existing concurrency ITs (`TransferConcurrencyIT`, `DeadlockFreedomIT`) and `EventPublicationRegistryIT` now
run with the audit listeners in every money transaction and stay green: no deadlock, and no extra outbox rows.

### 6. Docs

- `docs/adr/0008-hash-chained-audit.md`: canonical form, BEFORE_COMMIT vs `@ApplicationModuleListener`, the
  advisory lock and READ COMMITTED, deadlock argument, limits (a superuser can rewrite the tail; anchor the latest
  hash outside the DB later).
- This file.

### 7. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS** (6 min 23 s).
- event-contracts: 31 unit tests.
- core-app: 1145 unit tests and 641 integration tests.
- risk-aml-service: 99 unit tests and 48 integration tests (unchanged).

0 failures, 0 errors.

### Still open (rest of M10)

- `@Audited` annotation + aspect for compliance/admin actions without a domain event (KYC verify/approve/reject,
  document access, held-transfer review, reversals, reconciliation run).
- `GET /audit/events` and `GET /audit/verify` for AUDITOR (FR-A-2), with a SecurityMatrixIT row each; the
  "edit a row in SQL → verify reports the exact `seq`" test (disable the trigger as owner in the test).
- Hibernate Envers on `kyc_cases` and `wallets`; privacy module (export, erasure); ADR 0009 (field encryption).

### What to learn from this

1. **A hash chain is only as good as its canonical form.** The verifier must rebuild the exact bytes that were
   hashed, from what the database gives back. `jsonb` reorders keys, `timestamptz` keeps microseconds, `inet`
   rewrites IPv6, and floats print differently in different libraries. Each one was designed out, and a round-trip
   test proves it.
2. **Pin hash formats with an outside test vector.** A test that computes the expected hash with the same Java code
   only proves the code agrees with itself. A `sha256sum` result in the test catches any accidental format change.
3. **Choosing between "in the transaction" and "after commit" means choosing a failure mode.** BEFORE_COMMIT gives
   completeness (never a change without its audit) and fails closed. After-commit gives availability and needs
   at-least-once delivery plus dedup. For an audit trail in a regulated system, completeness wins.
4. **Lock, then read.** Reading the last hash before taking the lock is a classic check-then-act race. Under READ
   COMMITTED, a statement run after the lock is granted sees the previous holder's commit. Under REPEATABLE READ it
   may not, so the isolation level is part of the design.
5. **Back the lock with a constraint.** `UNIQUE (prev_hash)` turns a forked chain into a loud error instead of
   silent corruption. That's exactly what the mutation test showed when the lock was removed.
6. **Lock order also covers advisory locks.** Taking the global chain lock last (after the wallet row locks)
   means no transaction can hold it while waiting for a row, so it can't take part in a deadlock.
