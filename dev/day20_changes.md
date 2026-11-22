# Changes

## DAY20 (M10, FR-A-1, FR-A-2): `@Audited` endpoints, audit read API and chain verification

**Goal:** finish the audit half of M10 in `core-app` (SPEC FR-A-1, FR-A-2 §2, audit endpoints §5, §8 M10):
1. an `@Audited(action = "...")` annotation and an aspect on the compliance and admin endpoints (KYC verify,
   approve and reject, document view, freeze and unfreeze, transfer approve and reject, reconciliation run),
   recording actor, role, IP, user agent, correlation ID, entity and masked before/after;
2. `GET /api/v1/audit/events` (AUDITOR, paginated, filters) and `GET /api/v1/audit/verify`, which returns OK or
   the first broken sequence number;
3. `docs/adr/0008-hash-chained-audit.md` (extended with a Day 20 addendum).

Branch: `feature/day20-audit-api` (created from `master`), merged into `master`.

Decisions (approved in the plan):
- **New dependency `spring-boot-starter-aspectj`**, with ADR 0018 and a line in the CLAUDE.md stack. AspectJ was
  only on the classpath through another dependency; now it is declared explicitly.
- **The aspect sits around the controller and writes in its own transaction after the business commit.** This
  records refused attempts (REJECTED plus the error code) and works for read-only and ShedLock-run actions. It is
  not atomic with the change: if the audit insert fails after a commit, the response still goes out and an ERROR
  `AUDIT WRITE FAILED` is logged with the action, entity ID and correlation ID. Freeze, unfreeze and both approvals
  also keep their atomic Day 19 domain-fact rows.
- **A freeze has two events:** `WALLET_FREEZE` (the officer's request) and `WALLET_FROZEN` (the fact, which also
  covers AML freezes), joined by the correlation ID.
- **ADR 0008 is extended, not rewritten.** It already existed from Day 19.

---

### 1. Migration `V20__audit_add_user_agent_and_filters.sql`

- `user_agent varchar(256)` (nullable).
- Indexes `(actor_id, seq)` and `(occurred_at)` for the new filters. `(entity_type, entity_id, seq)` already
  existed from V19.

**Keeping Day 19 hashes valid:** `userAgent` is written to the canonical JSON only when it is not null. Every
older row has `user_agent = NULL`, so its canonical form and hash don't change. The general rule, now in
ADR 0008 §5: *a field added after the first version is left out of the hash when null.* The header is normalised
before hashing (control characters removed, trimmed, cut to 256 characters, blank becomes null), so the stored
value is exactly the hashed one.

### 2. Shared kernel `com.trustledger.core.shared.audit`

- **`@Audited(action, entityType, entityIdParam = "id", captureBefore = true)`**. `entityIdParam = ""` means
  "new entity, generate a UUIDv7 ID" (the reconciliation run).
- **`AuditSnapshotSource`** SPI: one bean per entity type returns the auditable state of an entity.
- **`AuditDetails`**: request and response records add facts about the call (`reasonLength`, run counts).

These live in `shared` (an OPEN module), so `kyc`, `wallet` and `payments` are audited without depending on
`audit`.

### 3. Audit module

**Domain**
- `AuditAction`: 9 request actions added (`KYC_CASE_VERIFY`, `KYC_CASE_APPROVE`, `KYC_CASE_REJECT`,
  `KYC_DOCUMENT_VIEW`, `WALLET_FREEZE`, `WALLET_UNFREEZE`, `TRANSFER_APPROVE`, `TRANSFER_REJECT`,
  `RECONCILIATION_RUN`). `AuditEntityType` gains `KYC_CASE`, `KYC_DOCUMENT` and `RECONCILIATION_RUN`.
- `AuditContext` and `AuditEvent` gain `userAgent`. The old constructors are kept, so Day 19 code and tests compile
  unchanged.
- `StoredAuditEvent`: a row exactly as read back. `toEvent()` rebuilds the hashed content, or throws for a row
  that can't be an audit event any more.
- `ChainVerifier` (pure, fed row by row) and `ChainVerification` (`OK`/`BROKEN`, `checkedEvents`, `lastSeq`,
  `lastHash`, `brokenSeq`, and a `reason` of `HASH_MISMATCH`, `PREV_HASH_MISMATCH` or `UNREADABLE`).
- `AuditEventFilter`: entityType, entityId, actorId, action and a half-open `[from, to)` range, validated
  (`VALIDATION_FAILED`).
- `AuditEventStore` gains `search`, `count` and `chainAfter(afterSeq, limit)`.

**Application**
- `AuditedActionRecorder`: looks up the snapshot sources by entity type (two sources for one type stop startup)
  and writes one event per call.
  - `succeeded`: `after` = the snapshot now, plus details, plus `"outcome": "SUCCEEDED"`.
  - `rejected`: `after` = details, plus `"outcome": "REJECTED"` and `"errorCode"`.
  
  The outcome keys are written last, so nothing can overwrite them.
- `AuditVerificationService`: walks the chain in keyset batches (`trustledger.audit.verify-batch-size`, default
  500) in one REPEATABLE READ read-only transaction. That gives a consistent snapshot without taking a lock.
- `AuditQueryService`: filtered, newest first, paginated with `trustledger.paging`.
- `AuditProperties` (validated record) and `AuditConfig`.

**Infrastructure**
- `AuditedAspect` (`@Around("@annotation(audited)")`, ordered `LOWEST_PRECEDENCE`, so it runs inside
  `@PreAuthorize`):
  1. Resolves the entity ID by parameter or `@PathVariable` name.
  2. Takes the before snapshot. If that fails it logs a warning and continues.
  3. Runs the call.
  4. Records SUCCEEDED, or REJECTED on a `TrustLedgerException` and rethrows it. Other exceptions are not
     audited.
  
  A failed audit write never changes the response.
- `JdbcAuditEventStore`: writes `user_agent`, builds the filter SQL from fixed fragments with bind parameters
  only, and parses `before`/`after` as any JSON value.
- `RequestAuditContextProvider`: reads the `User-Agent` header.

**Web**
- `AuditController` (`@PreAuthorize("hasRole('AUDITOR')")`):
  - `GET /audit/events?entityType=&entityId=&actorId=&action=&from=&to=&page=&size=` returns
    `{items, page, size, totalElements, totalPages}`. Each item is the stored row with its hashes.
  - `GET /audit/verify` returns `{status, checkedEvents, lastSeq, lastHash, brokenSeq, reason}` with
    `Cache-Control: no-store`.

### 4. Snapshot sources (what each module records about its entities)

| Entity | Bean | State recorded | Left out on purpose |
|---|---|---|---|
| `KYC_CASE` | `KycAuditSnapshots.CaseSource` | level, status, verifiedBy, approvedBy, rejectedBy | name, PAN, DOB, address, rejection reason |
| `KYC_DOCUMENT` | `KycAuditSnapshots.DocumentSource` | caseId, documentType | S3 key, presigned URL |
| `WALLET` | `WalletAuditSnapshotSource` | status, tier | balance, freeze reason |
| `TRANSFER` | `TransferAuditSnapshotSource` | status, amount (decimal string), currency, reviewedBy | the sender's note |

`KycDocumentRepository.findById` was added for the document snapshot. Endpoints still use `findByIdAndCaseId`.

### 5. Annotated endpoints

| Endpoint | action | entity | details |
|---|---|---|---|
| `POST /compliance/kyc-cases/{id}/verify` | `KYC_CASE_VERIFY` | KYC_CASE | |
| `POST /compliance/kyc-cases/{id}/approve` | `KYC_CASE_APPROVE` | KYC_CASE | |
| `POST /compliance/kyc-cases/{id}/reject` | `KYC_CASE_REJECT` | KYC_CASE | `reasonLength` |
| `GET /compliance/kyc-cases/{id}/documents/{docId}` | `KYC_DOCUMENT_VIEW` | KYC_DOCUMENT `{docId}`, no before | |
| `POST /compliance/wallets/{id}/freeze` | `WALLET_FREEZE` | WALLET | `reasonLength` |
| `POST /compliance/wallets/{id}/unfreeze` | `WALLET_UNFREEZE` | WALLET | `reasonLength` |
| `POST /compliance/transfers/{id}/approve` | `TRANSFER_APPROVE` | TRANSFER | |
| `POST /compliance/transfers/{id}/reject` | `TRANSFER_REJECT` | TRANSFER | |
| `POST /admin/reconciliation/run` | `RECONCILIATION_RUN` | new UUIDv7, no before | walletsChecked, mismatches, unlinked, truncated |

The annotated controller methods are now `public`, so the CGLIB proxy reliably intercepts them.

### 6. Docs

- `docs/adr/0008-hash-chained-audit.md`: Day 20 addendum.
  - §4: why the aspect sits around the controller (with a trade-off table), the two-row freeze, stale snapshots.
  - §5: the rule for adding a field to the hash.
  - §6: the verification algorithm, a tampering → reported seq/reason table, and the tail-deletion limit.
- `docs/adr/0018-aspectj-for-audited-endpoints.md`: new.
- `CLAUDE.md` stack: AspectJ (ADR 0018).
- `application.yml`: `trustledger.audit.verify-batch-size: 500`.

### 7. Tests

Unit:
- `ChainVerifierTest` (12): intact, empty, edited row, edited user agent, a rehashed row (caught at the next row),
  deleted row, swapped pair, missing genesis, non-object state, a fraction, a missing column, rows after a break.
- `AuditEventTest` (+4): no user agent leaves the Day 19 canonical JSON unchanged; a user agent is hashed;
  normalisation.
- `AuditEventFilterTest`, `AuditQueryServiceTest`, `AuditVerificationServiceTest` (batching, stopping at a
  break), `AuditedActionRecorderTest`.
- `AuditedAspectTest` (10, real `AspectJProxyFactory`): success, rejection, unexpected failure, failed write on
  both paths, failed snapshot, named path variable plus response details, generated ID, misconfigured parameter,
  unannotated method.
- `AuditedEndpointsTest`: exactly the 9 expected endpoints are annotated, each `public`, with a known
  action and type and a resolvable UUID parameter.
- `RequestAuditContextProviderTest` (+1): the user agent.

Integration (Testcontainers):
- `KycAuditedActionsIT`: verify and approve (who, IP, user agent, correlation ID, before/after, the correlated
  `KYC_LEVEL_UPGRADED` row); maker-approves → REJECTED `MAKER_CHECKER_VIOLATION`; reject keeps only
  `reasonLength` and no personal data; document view without the URL; another case's document → REJECTED
  `NOT_FOUND`.
- `TransferReviewAuditIT`: approve beside `TRANSFER_COMPLETED` (lower seq, same correlation, no note); reject, then
  a late approve → REJECTED `INVALID_STATE_TRANSITION`.
- `WalletAuditedActionsIT`: freeze and unfreeze beside their facts (a PAN or phone in the reason never reaches the
  trail); unknown wallet → REJECTED `NOT_FOUND`; reconciliation run with counts and actor ADMIN.
- `AuditApiIT`: newest-first entity history; combined filters and paging; bad filters return 400; verify OK up to
  the latest event. **SQL edit of a row → BROKEN at that exact seq (`HASH_MISMATCH`)**, and **a deleted row →
  BROKEN at the next seq (`PREV_HASH_MISMATCH`)**. Both bypass the triggers with
  `session_replication_role = replica` and restore the row in `finally`, so the shared chain stays intact.
- `AuditTrailIT`: the refused second freeze now also has its `WALLET_FREEZE` REJECTED row.
- `SecurityMatrixIT` (+12 rows): `/audit/events` and `/audit/verify`, with 401 anonymous, 403 for every other
  role, 200 for AUDITOR, and 405 for POST.

Two things the first full run taught:
- `MinKycIT` scans **all** `kyc_cases` for the customer PAN. A rejection reason is stored there in clear, so test
  reasons must not quote the shared fake PAN.
- Spring Security's `StrictHttpFirewall` already refuses CR/LF in headers before any controller runs. The
  user-agent normalisation is a second line of defence, covered by unit tests.

`./mvnw verify`: **BUILD SUCCESS**. core-app has 1189 unit and 669 integration tests, risk-aml-service has 99 unit and 48 integration tests, plus 31 event-contracts tests: 0 failures. (A rerun stopped for low memory had made `DeadlockFreedomIT` time out; on the clean run it passes in 7 s.)

### What to learn from it

- **Where an audit hook sits decides what it can promise.** Inside the business transaction you get atomicity;
  around the endpoint you get refused attempts and HTTP context. Writing both kinds of row, joined by the
  correlation ID, gets most of each.
- **Evolving a hashed format without breaking old hashes:** add new fields only when non-null. The old rows'
  canonical form doesn't change.
- **Verification reports a position and a reason.** Each kind of tampering leaves a different signature (same
  row, next row, unreadable). Removing the newest rows is invisible from inside the database, which is why the
  hash should be anchored externally.
- **An SPI in the shared kernel** lets one module observe others without depending on them, and keeps the
  "what is safe to log" decision with the module that owns the data.
