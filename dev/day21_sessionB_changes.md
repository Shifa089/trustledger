# Changes

## DAY21 Session B (M10, FR-PR-2): erasure, AML case events, ADR 0009

**Goal:** the erasure half of FR-PR-2 (SPEC §2 FR-PR-2, §8 M10 "Done when": *erasure leaves the ledger intact while
`users` personal fields are anonymised*):
1. `POST /api/v1/me/erasure-request`: 409 while the user has an open AML case;
2. erasure:
   - anonymise the personal fields and clear the blind indexes, and set the status to ERASED;
   - close the wallet only at a zero balance, otherwise refuse clearly;
   - leave the ledger, transfers and audit untouched;
   - write an audit event;
3. `docs/adr/0009-field-encryption.md`.

Branch: `feature/day21b-privacy-erasure`, created from `feature/day21a-privacy-export` because Session A was not
on `master` yet. Merging B into `master` brings in Session A as well.

### The choice: how core-app knows about open AML cases

Two options were offered:
- **(A) REST with a client-credentials token:** call the AML service on every erasure.
- **(B) A local copy in core-app, fed by AML events.**

**B was chosen** (the recommended option). Why:
- It reuses the Kafka pipeline and the outbox pattern of ADR 0007.
- It needs no new Keycloak client, secret or HTTP client.
- Erasure keeps working when the AML service is down.
- The check is a local query, made under the wallet lock in the erasure's own transaction.

The trade-off is eventual consistency: a case opened a moment ago may not have arrived yet. It is written up as an
amendment to ADR 0007.

Decisions:
- **Synchronous, in one transaction, returning 200.** The steps run in this order:
  1. lock the wallet (`SELECT ... FOR UPDATE`, the same lock money movement takes);
  2. check for an open AML case, the balance and hold, and money in flight;
  3. close the wallet;
  4. erase the identity data;
  5. record the request;
  6. publish the event, which the audit module writes before the commit.

  A refusal or failure rolls everything back, so a refused erasure leaves no row and changes nothing.
- **Nulls, not placeholder text.** E-mail, phone and name are set to `NULL` together with `email_hash` and
  `phone_hash`. A remaining blind index would still let anyone holding the key confirm that a given number was a
  customer. A database check enforces "an ERASED user holds no personal data".
- **The `users` row and its ID stay.** Ledger, transfers and audit refer to them, and an ID alone says nothing
  about the person.
- **"Zero balance" means empty and settled.** That is balance 0, hold 0, no transfer that is INITIATED or
  HELD_FOR_REVIEW, and no top-up that is CREATED or PENDING. Otherwise money could still land on a closed wallet.
  - A balance, a hold or money in flight → 409 `WALLET_NOT_EMPTY`, with the amount and what to do.
  - A frozen wallet → 409 `WALLET_FROZEN` (it is under a compliance hold).
- **Consent records stay; only their IP addresses are blanked.** The append-only trigger now allows exactly one
  change: `ip` from a value to `NULL`, with every other column unchanged. Everything else is still refused.
- **KYC records are kept.** That covers the encrypted PAN, date of birth, address and documents. PMLA requires
  keeping customer identification records for 5 years after the relationship ends (the default proposed in the plan
  and not objected to). Deleting them is a retention job for M14.
- **Erased once.** A partial unique index allows one COMPLETED erasure per user. The service turns every way of
  losing the race into the same 409 `INVALID_STATE_TRANSITION`:
  - the index violation;
  - an optimistic-lock failure on a user without a wallet;
  - finding the wallet already CLOSED after waiting for its lock.
- **No new dependencies.**

---

### 1. Event contract (`event-contracts`)

- `AmlCaseStatusChangedV1(eventId, occurredAt, walletId, caseId, status, open)`, topic
  `trustledger.aml.case-status-changed.v1`, keyed by wallet.
  - `status` is a string (tolerant reader). Consumers decide on the `open` flag.
  - No notes, no alert details.
- `ContractPrivacyTest` allowlist: + `status`, `open`. `ContractRecordsTest`: + 2 tests.

### 2. risk-aml-service: case events through an outbox

- `V5__aml_create_case_events.sql`: `aml.case_events`, an outbox shaped like `freeze_requests`.
  - Status is OPEN, CLOSED_FALSE_POSITIVE or ESCALATED.
  - Unique `(case_id, status)`.
  - A partial "pending" index.
- **Domain:** `CaseEvent` entity, plus the ports `CaseEventRepository`, `PendingCaseEvents` and
  `CaseEventPublisher`. `CaseEvent` refuses INVESTIGATING and drops an unsafe correlation ID.
- **Infrastructure:** `JpaCaseEventRepository` and `JdbcPendingCaseEvents` (`FOR UPDATE SKIP LOCKED`).
- **Messaging:** `KafkaCaseEventPublisher`, the same producer and headers as freeze requests.
- **Application:** `CaseEventRelay` and `CaseEventRelayJob`, the same algorithm and settings as the freeze relay.
  A failed send stops the run, so a "finished" event is never sent before its "opened" event.
- Where events are written:
  - `RuleEngine` writes "opened" when it opens a case;
  - `AmlCaseService.closeAsFalsePositive` and `escalate` write "finished";
  - assignment writes nothing.
- The topic is created locally next to the freeze-request topic.

### 3. core-app migrations

- `V23__identity_erasure.sql`:
  - `ck_users_erased_has_no_personal_data`;
  - the consent trigger function, replaced to allow `ip` → `NULL` only.
- `V24__privacy_erasure.sql`:
  - `privacy.aml_cases (case_id PK, wallet_id, open, changed_at, updated_at)`, with a partial index on open cases
    per wallet;
  - `uk_data_requests_one_erasure`.

### 4. Public API changes in other modules

| Module | Change |
|---|---|
| identity | `IdentityApi.erasePersonalData(userId)` (`MANDATORY`): `User.erase(now)`, `ConsentRepository.clearIpAddresses` (native SQL: `Consent` is `@Immutable`; the persistence context is deliberately not cleared so the caller's pending changes survive), flush so the DB check runs inside the call |
| wallet | `WalletApi.close(walletId)` (`MANDATORY`, must be locked first, like money movement); `Wallet.close(now)` refuses money, a hold, FROZEN, CLOSED |
| payments | `PaymentsApi.hasMoneyInFlight(walletId)` |
| privacy | public event `PersonalDataErased(userId, dataRequestId, walletId, erasedAt)` (IDs only) |
| audit | `AuditAction.PERSONAL_DATA_ERASED`; `AuditEntries.of(PersonalDataErased)`: before `{status: ACTIVE}`, after `{status: ERASED, dataRequestId, walletId, walletStatus: CLOSED}`; BEFORE_COMMIT listener |
| shared | `ErrorCode.WALLET_NOT_EMPTY`, `ErrorCode.AML_CASE_OPEN` (both 409) |

### 5. Privacy module

- **Domain:**
  - `DataRequest.erasure(userId, now)` creates the request already COMPLETED.
  - `AmlCaseProjection` port.
- **Infrastructure:** `JdbcAmlCaseProjection` writes with upserts.
  - "Opened" uses `ON CONFLICT DO NOTHING`.
  - "Finished" uses `ON CONFLICT DO UPDATE SET open = false WHERE open`.
  - So the events can arrive in any order or twice, and the result is the same. No `processed_events` is needed.
- **Application:** `AmlCaseProjectionService` and `ErasureService`.
- **Messaging:** `AmlCaseStatusChangedListener`, with retries and a dead-letter topic through the existing consumer
  config.
- **Web:** `POST /me/erasure-request` (`@PreAuthorize CUSTOMER`) → 200 with the COMPLETED request,
  `Cache-Control: no-store`.
- **Security:** `SecurityConfig` has a `POST /api/*/me/erasure-request` rule for CUSTOMER only.
- **Kafka topics:** `KafkaTopicsConfig` adds the new topic and its DLT.

### 6. Docs

- `docs/adr/0009-field-encryption.md` (new) covers:
  - AES-256-GCM with a random IV in the application;
  - keyed, normalised HMAC-SHA256 blind indexes and what they still leak;
  - keys and KMS;
  - every erasure rule above and why.
- `docs/adr/0007-separate-aml-service.md`: an amendment on case lifecycle events (option A vs B).
- `http/privacy.http`: the erasure request with every refusal.
- `package-info` for privacy, identity and audit.

### 7. Tests

Unit (core-app):
- `ErasureServiceTest` (8):
  - the happy-path order: lock → AML → in flight → close → erase → save → event;
  - no wallet;
  - each refusal changes nothing;
  - the amount appears in the message;
  - already erased;
  - each lost race (wallet CLOSED after the lock, optimistic lock, unique index) → 409.
- `WalletTest.Close` (5), `UserTest` (+2), `DataRequestTest` (+2), `AuditEntriesTest` (+1).
- `AuditListenersTest`: now expects 7 handlers.

Unit (risk-aml-service): `CaseEventTest` (5), `CaseEventRelayTest` (2), `RuleEngineTest` (an opened case writes
the event; joining an open case writes none).

Integration:
- `ErasureIT` (8):
  - **Done when:** a customer with consents and min KYC (over HTTP) receives ₹150.25 and sends it back. Erasure
    → 200 COMPLETED. Then:
    - the `users` personal columns and hashes are `NULL` and the status is ERASED;
    - lookup by phone and the notification contact are empty;
    - consents keep the same row count, but no IPs;
    - the wallet is CLOSED;
    - **transfers and journal lines are identical to before**;
    - **every earlier audit row is identical**, there is one new `PERSONAL_DATA_ERASED` without personal data, and
      `/audit/verify` returns OK;
    - the request is listed in `/me/data-requests`.
  - A second erasure → 409, and nothing changes.
  - Refusals, each leaving everything as it was:
    - money on the wallet → 409 `WALLET_NOT_EMPTY` with the amount;
    - a pending top-up → 409 `WALLET_NOT_EMPTY`;
    - a frozen wallet → 409 `WALLET_FROZEN`.
  - **An open AML case over Kafka:** "opened" → 409 `AML_CASE_OPEN`; "finished" → erasure succeeds.
  - A user without a wallet → erased, and the audit event has no `walletId`.
  - **Two racing requests → exactly one succeeds**, with one ERASURE row and one audit event.
- `AmlCaseProjectionIT` (4):
  - opened then finished over Kafka;
  - a late "opened" doesn't reopen a case;
  - duplicates change nothing;
  - a wallet is open while any of its cases is.
- `PrivacyMigrationIT` (+4):
  - one completed erasure per user;
  - an ERASED user with personal data or a hash is refused;
  - consents allow only `ip` → `NULL` (once), and nothing else, including DELETE;
  - the projection index exists.
- `KafkaTopicsIT`: the new DLT.
- `SecurityMatrixIT`: +7 rows for `POST /me/erasure-request`.
  - 401 for anonymous; 403 for no roles, officer, admin and auditor.
  - CUSTOMER gets past security to 404, because this MockMvc has no JIT filter.
  - `GET` on it → 403.
- risk-aml-service `CaseEventPublishingIT`: a real case is opened by an R3 transfer, then assigned and closed by
  an officer. Two records reach Kafka in order (`open=true`, then `open=false`), without the officer's notes.

Test status at commit time: **full `./mvnw verify` is green** (BUILD SUCCESS). That covers event-contracts (36),
core-app unit (1249) and integration (719), and risk-aml-service unit (106) and integration (49), with no failures.
It also confirms Session A, which this branch includes.

### Not in this session (later)

- **Keycloak account:** disabling the erased customer's account in Keycloak (admin API). Until then they can still
  sign in, but see an empty profile and a closed wallet.
- **Backfill:** cases opened before this release need a one-off backfill of `privacy.aml_cases` in a real
  deployment.
- **Retention:** deleting KYC records 5 years after the relationship ends, and keeping backup retention short
  (M14).
- **Auditing exports:** an audit event for data-export requests (FR-A-1).

### What to learn from it

- **A local projection versus a remote call:** a projection trades freshness for availability and atomicity. Here
  that is the right trade, because the check runs inside a lock and the other service may be down.
- **Make projections order-independent instead of de-duplicating.** If a state only moves one way (open →
  finished), "insert if absent" and "set finished" upserts make redeliveries and reordering harmless, without a
  `processed_events` table.
- **Erasure is not deletion.** Remove what identifies the person, including blind indexes, which are
  pseudonymous but still confirm a guess. Keep what the law or the ledger needs, keyed by IDs that say nothing on
  their own.
- **Lock first, then check.** Taking the money-movement lock before reading the balance means no transfer can land
  between the check and the close. That is the same reason a transfer locks before it debits.
- **Every way of losing a race should give the same answer.** A unique index, an optimistic-lock version and a
  re-read after the lock each catch a different race, and all of them map to the same 409.
- **Append-only can have one precise exception.** Write the exception in the trigger itself (only `ip` → `NULL`,
  all else equal), so the database still refuses everything else.
