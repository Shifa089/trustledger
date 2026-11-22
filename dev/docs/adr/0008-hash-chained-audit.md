# ADR 0008: Hash-chained audit trail, written in the business transaction

- Status: Accepted
- Date: 2026-11-22
- Spec: docs/SPEC.md §2 FR-A-1, FR-A-2, §4 `audit.audit_events`, §8 M10

## Context

FR-A-1 says every state-changing action writes an audit event (actor, role, action, entity, masked before/after, IP,
correlation ID), hash-chained to the previous event. FR-A-2 says auditors can verify the chain. An audit trail has
to show that it hasn't been changed: a DBA or an attacker with SQL access could otherwise edit, delete or reorder
history without leaving a trace. It also has to be complete. A transfer that committed without its audit row is a
gap the regulator will ask about.

Three questions:

1. How is tampering made detectable?
2. When is the audit row written relative to the business change: in the same transaction, or after commit?
3. How do concurrent writers keep the chain linear?

## Decision

### 1. A SHA-256 hash chain over a canonical form

`hash = SHA-256(prev_hash || canonicalJson)`, hex in lower case; the first row's `prev_hash` is the genesis hash
(64 zeros). Changing any hashed field of row *n* changes its hash, so row *n+1*'s `prev_hash` no longer matches. A
deleted row leaves a broken link, and a reordered row breaks two.

The canonical JSON is written by our own small writer (`CanonicalJson`), not by Jackson:

- keys are sorted at every depth, there is no whitespace, and `null` fields are written out;
- `occurredAt` is cut to microseconds (the precision of `timestamptz`) and always printed with six fraction digits;
- only strings, booleans, integral numbers, maps, lists and null are allowed. Fractional numbers are refused.
  Amounts are decimal strings anyway (SPEC §6), and a float's text form isn't stable;
- `ip` is stored as `varchar`, not `inet`, which would rewrite IPv6 addresses.

The rule is that **the hash can be recomputed from the stored row alone**, years later, whichever JSON library is
in use then. `jsonb` reorders keys and drops whitespace, and that's fine because we sort again when verifying.
`seq` is not hashed: a rolled-back writer leaves a gap in the sequence, and `prev_hash` already fixes each row's
position. A test vector computed outside Java (`sha256sum`) pins the format.

Database safety nets: triggers refuse UPDATE, DELETE and TRUNCATE (as for the journal), `hash` is unique, and
**`prev_hash` is unique**, so the database itself refuses a fork (two rows extending the same predecessor).

### 2. Written in the publishing transaction, just before commit

The listeners are `@TransactionalEventListener(phase = BEFORE_COMMIT)` with `@Order(LOWEST_PRECEDENCE)`. The writer
is `@Transactional(propagation = MANDATORY)`.

Options considered:

| | BEFORE_COMMIT in the same transaction (chosen) | `@ApplicationModuleListener` (after commit, new transaction) |
|---|---|---|
| Completeness | The audit row and the change commit or roll back together | A committed change may briefly lack its row; the outbox redelivers |
| Duplicates | None, because nothing is redelivered | At-least-once delivery, so a dedup key per event is needed |
| Actor, IP, correlation | Still on the request thread | Gone (another thread); every event would have to carry them |
| Cost | The chain lock is held until the business commit | The lock is held only for the short audit transaction |
| Failure | An audit failure rolls the business change back | The business change survives and the audit is retried |

For a regulated wallet, "never a committed money movement without its audit row" is worth more than throughput,
and failing closed is the right behaviour when the audit can't be written. The event externalization to Kafka and
the notifications stay on `@ApplicationModuleListener`. Spring Modulith records publications for AFTER_COMMIT
listeners only, so the audit listeners don't add rows to `event_publication`.

An event published outside a transaction is not audited (`fallbackExecution = false`). Every publisher today is a
`@Transactional` application service, and the IT suite checks each event.

### 3. One writer at a time: `pg_advisory_xact_lock`

The writer takes `pg_advisory_xact_lock(0x544C5F4155444954)` ("TL_AUDIT"), reads the latest hash
(`ORDER BY seq DESC LIMIT 1`), computes and inserts. The lock is released at commit or rollback. This depends on
READ COMMITTED (the default): the read is a new statement after the lock was granted, so it sees the previous
holder's committed row. Under REPEATABLE READ it could see an older snapshot. The unique `prev_hash` would then
refuse the insert instead of storing a fork.

Alternatives: `SELECT ... FOR UPDATE` on the last row (no row exists for the first event, and the last row changes
under you), `SERIALIZABLE` (retries everywhere), or a single-threaded writer queue (not transactional with the
business change).

**Deadlocks:** business row locks (wallets in ascending ID order, ADR 0004) are always taken first and the advisory
lock last, at BEFORE_COMMIT and lowest precedence. Nothing takes the advisory lock and then a row lock, so there's
no cycle. The existing concurrency ITs (`TransferConcurrencyIT`, `DeadlockFreedomIT`) now run with the audit in
place.

### Actor, role and context

The actor is our user ID for the token's subject (the same kind of ID as `frozen_by` and `approved_by`). A token
with several roles is recorded with its most privileged role (ADMIN > COMPLIANCE_OFFICER > AUDITOR > CUSTOMER).
With no token (the AML freeze consumer, scheduled jobs, listeners after commit) the actor is SYSTEM with no ID. The
IP is the servlet request's remote address. The correlation ID comes from the domain event's `EventMetadata` when it
carries one, otherwise from the MDC. Before/after go through `AuditMasker`, which masks personal fields by key at
any depth. Today's events carry no personal data, so the masker is a safety net for the `@Audited` aspect.

## Consequences

- Audited transactions commit one at a time. With commits of a few milliseconds that is hundreds per second, far
  above this project's load. If it ever matters, the next step is partitioned chains (one per entity type, each
  with its own lock key), anchored periodically to a combined hash.
- The chain proves the rows weren't changed *after* they were written. It doesn't stop someone with superuser
  access from disabling the trigger and rewriting the whole tail with new valid hashes. Periodically publishing
  the latest hash outside the database (for example to S3 with Object Lock) closes that gap. That's out of scope
  for M10.
- If the audit write fails, the business action fails too. That's intended (fail closed), and it means the audit
  code is on the money path and gets the same test rigour.
- Day 20: `GET /audit/verify` walks the chain with the same `AuditEvent.canonicalJson()` and reports the first
  broken `seq`. The `@Audited` aspect covers compliance and admin actions that publish no domain event.
