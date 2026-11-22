# Changes

## DAY21 Session A (M10, FR-PR-2): privacy module, asynchronous data export to S3

**Goal:** the export half of FR-PR-2 in `core-app` (SPEC §2 FR-PR-2, privacy table §4, privacy endpoints §5, §8 M10):
1. a `privacy` module with the `privacy.data_requests` table;
2. `POST /api/v1/me/data-export`: processed asynchronously. The user's profile, consents, KYC status (no documents),
   wallet, transfers and top-ups are collected **through each module's public API** and written as one JSON file
   to S3;
3. `GET /api/v1/me/data-requests`: the caller's requests, with a **10-minute presigned URL** for each completed
   export.

Erasure (`POST /me/erasure-request`, blocked while an AML case is open) is Session B.

Branch: `feature/day21a-privacy-export` (created from `master`), merged into `master`.

Decisions:
- **Asynchronous with the outbox, but without a transaction around the upload.** The request row and an internal
  `DataExportRequested` event are stored in one transaction. After the commit, an `@Async @TransactionalEventListener`
  builds the file. It is *not* an `@ApplicationModuleListener`, because that annotation opens a transaction around
  the whole listener, and the S3 upload would then hold a database connection open during a remote call. Spring
  Modulith still records the publication in `shared.event_publication` (AFTER_COMMIT listener) and completes it
  when the listener returns, so a crash mid-export is retried on restart.
- **New public APIs instead of reaching into other modules:** `KycApi`, `PaymentsApi` and
  `IdentityApi.exportPersonalData`. `WalletApi.findByUser` already existed.
- **The export holds the user's own data in clear.** That is the point of a right-of-access export. The file lives
  only in S3 (SSE-S3), and the link lives 10 minutes. Each record carrying clear data overrides `toString()`, so an
  accidental log line shows nothing personal.
- **KYC status only:** level, status and decision times per case. No PAN, name on PAN, date of birth, address,
  documents or officer IDs.
- **Counterparties are wallet IDs only.** Nothing about the other person goes into someone else's export.
- **A separate bucket for exports** (`trustledger.privacy.storage.*`). Exports are short-lived copies, so their
  lifecycle rule (M14) must never apply to KYC documents. The adapter **owns its S3 client** instead of publishing
  an `S3Client` bean: KYC injects its `S3Client` by type, and a second bean would make that injection ambiguous.
- **No new dependencies.**

---

### 1. Migrations

- `V21__privacy_create_data_requests.sql`: `privacy.data_requests` (`id`, `user_id`, `type` EXPORT/ERASURE,
  `status` PENDING/COMPLETED/FAILED, `result_s3_key`, `failure_reason`, `requested_at`, `completed_at`, timestamps,
  `version`). Database safety nets:
  - `result_s3_key` exactly on a COMPLETED EXPORT, and unique;
  - `completed_at` exactly when finished, and never before `requested_at`;
  - `failure_reason` exactly when FAILED;
  - partial unique index `uk_data_requests_one_pending (user_id, type) WHERE status = 'PENDING'`: one request in
    progress per user and type, even under a race.
  - No personal data in the table, and no FK to `identity` (other schema).
- `V22__payments_index_transfers_receiver.sql`: `ix_transfers_receiver_created (receiver_wallet_id, created_at)`,
  for the receiver half of "every transfer this wallet sent or received". The sender half already had its V11 index.
  The history in M11 will need it too.

### 2. New public APIs (other modules)

| Module | API | Returns | Left out on purpose |
|---|---|---|---|
| identity | `IdentityApi.exportPersonalData(userId)` | `PersonalDataExport`: email, phone, full name in clear, status, registered at, the full consent history (purpose, version, granted, time, IP) | the Keycloak subject and blind-index hashes. `toString()` masks everything (IP omitted) |
| kyc | `KycApi.statusOf(userId)` (new) | `KycStatusSummary`: current level (`NONE`/`MIN`/`FULL`) plus each case's level, status, submitted/verified/approved/rejected times and the rejection reason the customer already sees on `/kyc/me` | PAN, name, DOB, address, documents, officer IDs |
| payments | `PaymentsApi.transfersOf(walletId)`, `topupsOf(walletId)` (new) | `TransferRecord` and `TopupRecord`, oldest first, every status, amounts as `Money` | journal entry IDs, reviewers. `TransferRecord.toString()` never prints the note |

- `KycQueryService.currentLevel` was extracted so `/kyc/me` and `KycApi` compute the level the same way.
- New repository methods: `TransferRepository.findInvolving` (JPQL, sender OR receiver) and
  `TopupRepository.findByWalletIdOrderByCreatedAtAscIdAsc`.

### 3. Privacy module (`com.trustledger.core.privacy`)

**Domain**
- `DataRequest` entity. `export(userId, now)` creates a PENDING export, then `completeExport(now)` or
  `fail(reason, now)`, once. A second outcome throws `INVALID_STATE_TRANSITION`.
  - `exportKey()` = `exports/<userId>/<requestId>.json`. It is deterministic, so reprocessing overwrites the same
    object.
  - A reason is cut to 64 characters, and a clock that went backwards never finishes before the request.
- `DataRequestType`, `DataRequestStatus`, `DataRequestRepository` port, and the `ExportStorage` port
  (`putJson`, `presignGet`).

**Application**
- `DataExportService.requestExport(userId)` (`@Transactional`):
  - an export already PENDING → `409 REQUEST_IN_PROGRESS`, from the service check or from the unique index on a
    race (`saveAndFlush`);
  - otherwise saves PENDING and publishes `DataExportRequested(requestId, userId)` in the same transaction.
- `DataExportListener`: `@Async @TransactionalEventListener` (AFTER_COMMIT) → `DataExportProcessor.process`.
- `DataExportProcessor` (no transaction):
  1. skips a request that is no longer PENDING (idempotent redelivery);
  2. `DataExportAssembler` collects the data through the four APIs;
  3. pretty-printed JSON goes to S3;
  4. `DataRequestWriter.completeExport` records the outcome in a short transaction of its own.
  
  On failure it records `FAILED` with an **error code** (`STORAGE_UNAVAILABLE`, `NOT_FOUND`) or the **exception
  class name**, never a message. If even recording the failure fails, the exception escapes and the publication
  stays incomplete, so it is retried.
- `DataExportDocument`, format `trustledger-data-export/v1`: `profile`, `consents`, `kyc`, `wallet` (`null` without
  KYC), `transfers` (direction `SENT`/`RECEIVED`, counterparty wallet ID, note), `topups`. Amounts are decimal
  strings, times are UTC ISO-8601.
- `DataRequestQueryService.list(userId)`: newest first, at most `max-listed-requests`. A COMPLETED export gets a
  **fresh** presigned link on every call. The link is never stored. The failure reason is not shown to customers.
- `PrivacyProperties` (`trustledger.privacy.export-link-ttl: 10m`, validated 1 s–10 min; `max-listed-requests: 50`,
  1–200) and `PrivacyConfig`.

**Infrastructure**
- `JpaDataRequestRepository`.
- `S3ExportStorage`: its own `S3Client` and `S3Presigner`, closed on shutdown.
  - SSE-S3 (AES256) on every object, content type `application/json`.
  - Presigned GET with `Content-Disposition: attachment; filename="trustledger-data-export.json"`.
  - Vendor errors become `STORAGE_UNAVAILABLE`.
- `PrivacyStorageProperties` (`trustledger.privacy.storage.*`, same shape as KYC's; the secret is never printed) and
  `PrivacyStorageConfig`. `create-bucket` applies in the local profile and tests only.

**Web**
- `PrivacyController` (`@PreAuthorize("hasRole('CUSTOMER')")`). The user always comes from the token, so there is
  no ID to tamper with (no BOLA).
  - `POST /me/data-export` → **202 Accepted**, `Location: /api/v1/me/data-requests`, body
    `{id, type, status: PENDING, requestedAt}`.
  - `GET /me/data-requests` → `{items:[{id, type, status, requestedAt, completedAt, download:{url, expiresAt}}]}`
    with `Cache-Control: no-store`, because the links open a file of personal data.
- `SecurityConfig`: `POST /api/*/me/data-export` and `GET /api/*/me/data-requests` are CUSTOMER only. Every other
  method on them falls through to `denyAll()`.

### 4. Configuration

- `application.yml`: `trustledger.privacy.*`. The bucket comes from `TRUSTLEDGER_PRIVACY_BUCKET` (default
  `trustledger-data-exports`) and the region from `AWS_REGION`.
- `application-local.yml`: LocalStack bucket `trustledger-privacy-local`, created at startup.
- Tests: bucket `trustledger-privacy-test`. `TestcontainersConfiguration` gives it the LocalStack endpoint and
  credentials.
- `http/privacy.http`: manual requests.

### 5. Tests

Unit:
- `DataRequestTest` (10): pending state, deterministic key, complete and fail, reason truncation, no second outcome,
  clock skew.
- `DataExportServiceTest` (3): stores and publishes; pending → 409; unique-index race → 409, nothing published.
- `DataExportProcessorTest` (7):
  - success writes the JSON under the request's key, then completes;
  - a request that is not pending, or unknown, is skipped;
  - a storage failure → `STORAGE_UNAVAILABLE`;
  - an unexpected error → class name only (the message held an e-mail address and phone number);
  - a deleted user → `NOT_FOUND`;
  - if recording the failure also fails, the exception escapes so the event is retried.
- `DataExportDocumentTest` (5): mapping and decimal amounts, SENT/RECEIVED direction and counterparty, no-wallet
  case, `toString()` of the document, `PersonalDataExport` and `TransferRecord` without personal data.
- `DataRequestQueryServiceTest` (2): a link only for a COMPLETED export, with the configured TTL; no requests, no
  signing.
- `PrivacyPropertiesTest` (10), `PrivacyStoragePropertiesTest` (3).

Integration (Testcontainers: PostgreSQL, Kafka, LocalStack, ...):
- `DataExportIT` (7):
  - **End to end:** the customer gives consents, completes min KYC over HTTP (the wallet and ledger link follow
    asynchronously), receives ₹150.25 from a FULL sender through the real `TransferService`, and has a pending
    top-up. Then `POST` → 202 and `Location`; the list polls to COMPLETED; `expiresAt` is about now + 10 min and
    the URL has `X-Amz-Expires=600`.
  - **The file**, downloaded from LocalStack through the link (`attachment`), has: email, phone and name in clear,
    both consents with IP, KYC `MIN`/`APPROVED`, wallet `MIN` with balance `150.25`, the transfer as `RECEIVED` from
    the sender's wallet with its note, and the top-up. It has **no** PAN, DOB, name on PAN, document fields, or
    sender user ID or name.
  - **The row and the outbox:** the row has the result key, and the `event_publication` row is completed.
  - a user without KYC gets `wallet: null` and empty lists;
  - a PENDING export → a second `POST` gets 409 `REQUEST_IN_PROGRESS`; the list shows it without a link;
  - a new export after a finished one; the list is newest first and both have links;
  - another customer sees an empty list;
  - reprocessing a completed request changes nothing;
  - `Cache-Control: no-store`.
- `PrivacyMigrationIT` (7): every valid shape accepted; one pending per user and type; result key only on a
  completed export and unique; completion time, reason and time order; unknown type or status; the V22 index exists.
- `SecurityMatrixIT` (+14 rows):
  - `POST /me/data-export`: 401 anonymous, 403 for no roles, officer, admin and auditor; `GET` on it → 403.
  - `GET /me/data-requests`: 401 anonymous, 403 for other roles; CUSTOMER gets past security to 404. This
    MockMvc has no JIT filter, the same as the `/transfers/{id}` row. `POST` on it → 403.
- `ModularityTests` and `ArchitectureRulesTest` stay green. privacy depends on identity, kyc, wallet and payments
  through their APIs only, and nothing depends on privacy.

Test status at commit time:
- Passed in targeted runs: all new unit tests (40), `ModularityTests`, `ArchitectureRulesTest`, and the integration
  tests `DataExportIT`, `PrivacyMigrationIT`, `FlywaySchemasIT` and `SecurityMatrixIT`. The one matrix row that
  failed (expected 200) was corrected to 404, as explained above.
- The first full `./mvnw verify` ran all 1229 core-app unit tests with 1 failure:
  `RazorpayGatewayAdapterTest.wrongKeysAreRejectedWithoutLeakingTheSecret`. The 2 s gateway time limit fired while
  the machine was loaded, so the call ended as "unavailable" instead of "rejected". That code is untouched today,
  and the test passes when run alone (25/25). Because the unit phase failed, the full integration suite did not
  run in that build.
- A second full `./mvnw verify` was stopped because the machine ran low on memory.
- **A full green `./mvnw verify` still has to be confirmed before merging to `master`.**

### Not in this session (later)

- Erasure (`POST /me/erasure-request`, anonymise `users`, keep ledger, transfers and audit; refused while an AML
  case is open): Session B. `data_requests` already accepts `ERASURE`.
- Auditing export requests (FR-A-1): a privacy event for the audit trail, decided together with erasure in
  Session B.
- An S3 lifecycle rule that deletes export files after a few days (Terraform, M14).
- Very large histories are read in one list per module. That is fine for a portfolio dataset; streaming or paging
  can come later.

### What to learn from it

- **"Asynchronous" has two halves:** *don't lose the work* (the event is stored with the request, in the outbox),
  and *don't hold resources while waiting* (no transaction or connection open during the S3 upload). An
  `@ApplicationModuleListener` gives the first but wraps everything in a transaction. A plain AFTER_COMMIT
  `@Async` listener plus small explicit transactions gives both.
- **Idempotency by state plus a deterministic key:** a redelivered event finds the request no longer PENDING and
  stops. A crash between upload and commit re-uploads to the same key, so it never leaves a second file.
- **A right-of-access export is a controlled exception to masking.** Clear data goes to exactly one place (an
  encrypted object behind a short-lived link), records that carry it mask their `toString()`, and the export holds
  only *the subject's* data. Counterparties stay wallet IDs.
- **Presigned links are computed, not stored.** Signing is local (no S3 call), so each `GET` hands out a fresh
  10-minute link, and nothing in the database can leak a working URL.
- **A database index can enforce a business rule:** a partial unique index turns "one export in progress"
  into a guarantee under concurrency, and the service maps the violation to the same 409 as its own check.
