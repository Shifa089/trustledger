# Changes

## DAY5 Session B: Milestone M3 (part 2): full KYC, documents in S3, compliance review

**Goal:** finish the `kyc` module (FR-KYC-2, FR-KYC-3, FR-KYC-4). The parts:
- **Table.** `kyc.kyc_documents`: metadata only, with database checks on size, type and hash.
- **Storage.** A `DocumentStorage` port with an S3 adapter (AWS SDK v2). LocalStack is used locally and in tests. The bucket is created at startup only in local and test.
- **Customer endpoints.**
  - `POST /api/v1/kyc/full` opens a FULL case.
  - `POST /api/v1/kyc/cases/{id}/documents` uploads a document:
    - only the case owner can upload
    - only PDF, JPEG or PNG, decided by the file's magic bytes
    - at most 5 MB
    - SHA-256 stored
- **Compliance endpoints.**
  - A paginated queue and a case detail view.
  - Verify (maker) and approve (checker). The same officer approving gets 409 `MAKER_CHECKER_VIOLATION`.
  - Reject, with a reason.
  - Document view through a presigned URL valid for 5 minutes.
  - Approval publishes `KycLevelUpgraded(FULL)`.

Branch: `feature/m3-kyc-full` (created from `master` after Session A), merged into `master`.

Decisions approved in the plan:
1. AWS SDK v2 (S3 only) and `testcontainers-localstack` as new dependencies, with ADR 0011.
2. Verify needs every required document type (`PAN_CARD`, `ADDRESS_PROOF`, configurable), so there is no DRAFT status.
3. A new detail endpoint `GET /compliance/kyc-cases/{id}`. The queue items carry no personal data.
4. Full KYC requires approved minimum KYC (403 `KYC_REQUIRED`).

---

### 1. How to verify Day 5 Session B

Run these from `dev/`. Start **Docker Desktop** first.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build: 464 core-app unit tests, 262 core-app ITs (PostgreSQL, Redis, Keycloak **and LocalStack** in Testcontainers), AML IT, Modulith check, JaCoCo. Same as CI. |
| 2 | `./mvnw -pl core-app verify -DskipUnitTests -Dit.test='FullKycIT,ComplianceKycIT,S3DocumentStorageIT,KycDocumentMigrationIT,KycLevelUpgradedScenarioIT'` | Just the new kyc ITs. |
| 3 | `docker compose -f infra/docker-compose.yml up -d postgres redis keycloak localstack` | Local stack for the live check. **Recreate LocalStack** if it was already running (`up -d --force-recreate localstack`): compose now sets `S3_SKIP_SIGNATURE_VALIDATION=0`. |
| 4 | `./mvnw install -DskipTests`, then `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local` | Flyway applies **V4**. The log shows `Created KYC document bucket trustledger-kyc-local`. |
| 5 | Run `http/auth.http` (tokens and customer1 min KYC), then `http/kyc.http` top to bottom | full KYC → upload PNG and PDF (`http/samples/`, fake files) → a text file disguised as PDF gives 415 → queue → detail → presigned link → officer1 verifies → officer1 approving gives 409 → officer2 approves → `/kyc/me` shows FULL. |
| 6 | Open the `url` from the document-link response in a browser, then again after 5 minutes | Downloads at first, then 403 (expired). |
| 7 | `docker compose -f infra/docker-compose.yml exec postgres psql -U trustledger -d trustledger -c "select doc_type, content_type, size_bytes, sha256, s3_key from kyc.kyc_documents"` | Metadata only, no file names, no bytes. |

**Results:** `./mvnw clean verify` ended in **BUILD SUCCESS**.
- **Unit tests:** core-app 464, up from 387. New: `DocumentFormatTest` 21, `KycReviewServiceTest` 11, `KycDocumentServiceTest` 9, `KycDocumentTest` 7, `KycCaseDocumentsTest` 6, `KycDocumentPropertiesTest` 5, `FullKycServiceTest` 4, `KycStoragePropertiesTest` 4, `ErrorCodeTest` +10 rows.
- **Integration tests:** core-app 262, up from 187. New: `FullKycIT` 17, `ComplianceKycIT` 16, `KycDocumentMigrationIT` 7, `S3DocumentStorageIT` 5, `KycLevelUpgradedScenarioIT` +2, `SecurityMatrixIT` +28 (79 → 107). The AML IT passed (1/1) and `ModularityTests` stayed green.
- **Line coverage (JaCoCo):** `kyc` 100%, `kyc.domain` 100%, `kyc.application` 99.1%, `kyc.web` 91.2%, `kyc.infrastructure` 91.0%.
- **Log check:** the build log has no test address, PAN, date of birth or presigned signature (`X-Amz-Signature`).

I did not run the live check against the compose stack (steps 3 to 7) in this session.

---

### 2. What changed and why

#### Database (`V4__kyc_create_kyc_documents.sql`)

| Object | Change | Why |
|---|---|---|
| `kyc.kyc_documents` | `id`, `case_id`, `doc_type`, `s3_key`, `content_type`, `size_bytes`, `sha256`, `uploaded_by`, `created_at` | SPEC §4, plus `uploaded_by` (who) and `created_at`. **No file name**: it can contain personal data ("Rahul_PAN.pdf") and nothing needs it. |
| `ck_kyc_documents_size` | `size_bytes BETWEEN 1 AND 5242880` | The 5 MB rule in the database too, and never an empty file. |
| `ck_kyc_documents_content_type`, `ck_kyc_documents_doc_type` | Only `application/pdf`, `image/jpeg`, `image/png`; only `PAN_CARD`, `ADDRESS_PROOF`, `PHOTO` | Same lists as the code. |
| `ck_kyc_documents_sha256` | `sha256 ~ '^[0-9a-f]{64}$'` | One canonical form, so hashes compare with `=`. |
| `fk_kyc_documents_case` | FK to `kyc.kyc_cases` | Allowed: same schema. The no-FK rule is about *other* modules' schemas. |
| `uk_kyc_documents_s3_key`, `ix_kyc_documents_case`, `ix_kyc_cases_queue (level, status, created_at)` | Unique key, list per case, queue order | The queue query is an index range scan. |

#### KYC module

| File | Change | Why |
|---|---|---|
| `domain/DocumentFormat` | PDF `%PDF-`, JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A` at offset 0 | The file name and the client's `Content-Type` are attacker-controlled; the first bytes are not a claim, they are the file. |
| `domain/DocumentType`, `domain/KycDocument` (+ repository port) | Immutable metadata entity. The factory checks the size (1 B to 5 MB) and the hash format. | Same rules as the database, enforced earlier. |
| `domain/DocumentStorage` | Port: `put` (with SHA-256), `delete`, `presignGet` | The application layer never sees the AWS SDK. Swapping the store means writing one new adapter. |
| `domain/KycCase` | `requireAcceptsDocuments()` (FULL and SUBMITTED only), `isOwnedBy()` | Once a maker has verified, the evidence is frozen. A later upload would bypass review. |
| `domain/KycCaseRepository` + `JpaKycCaseRepository` | `findById`, `lockById` (`PESSIMISTIC_WRITE`, i.e. `SELECT ... FOR UPDATE`), queue page, `existsBy...Status` | Decisions and the final upload step lock the case row (see section 5). |
| `application/FullKycService` | Needs approved MIN (403 `KYC_REQUIRED`). Rejects a second live FULL case (409). Inserts with `saveAndFlush`; the partial unique index from V3 turns a race into 409. | Same pattern as Session A's minimum KYC. |
| `application/KycDocumentService` + `KycDocumentTransactions` | Upload in five steps, detailed below. | S3 is a remote call, so it runs **outside** any transaction and without a lock. |
| `application/KycReviewService` | `queue` (FULL cases, oldest first, page size up to 100), `detail`, `verify`, `approve`, `reject`, `documentLink`. Decisions lock the case. Approve flushes **before** publishing `KycLevelUpgraded(FULL)`. | If the database refuses the approval (maker-checker check), no event goes out. |
| `application/KycDocumentProperties` | `trustledger.kyc.documents.*`: `max-size: 5MB` (startup fails above 5 MB), `max-per-case: 10`, `required-types: [PAN_CARD, ADDRESS_PROOF]`, `view-link-ttl: 5m` (startup fails above 5 minutes) | No hard-coded limits (CLAUDE.md), and no unsafe configuration either. |
| Views: `FullKycCaseView`, `KycDocumentView`, `KycQueueItem`/`KycQueuePage`, `KycReviewCaseView`, `DocumentLinkView` | Records. Queue items have no personal data. The detail view has the address, with a masked `toString()`. The link view's `toString()` hides the URL. | Entities never leave the module. A presigned URL is a bearer credential: it must never be logged. |
| `infrastructure/S3StorageConfig`, `S3DocumentStorage`, `KycStorageProperties` | `S3Client` + `S3Presigner` on `UrlConnectionHttpClient`, path-style for LocalStack. **SSE-S3 (AES256)** and **`x-amz-checksum-sha256`** on every put. Downloads use `response-content-disposition=attachment`. Static credentials only if configured, otherwise the default AWS chain. The bucket initializer runs only when `create-bucket=true`. SDK exceptions become 503 `STORAGE_UNAVAILABLE`. | The S3 side refuses bytes that don't match our hash. Objects are encrypted at rest. A browser downloads the file instead of rendering it, so a crafted PDF can't run in our origin. |
| `web/KycController` | `POST /kyc/full` and the multipart `POST /kyc/cases/{id}/documents` (`file` + `type`) | CUSTOMER only, and the user comes from the token. |
| `web/ComplianceKycController` | Queue, detail, verify, approve, reject (`@NotBlank @Size(max=500) reason`), document link with `Cache-Control: no-store` | `@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")` on the class, plus the existing `/compliance/**` URL rule. The maker and checker come from the token. |
| `web/Callers` | Token subject → our user ID, shared by both controllers | Officers are JIT-provisioned like customers, so `verified_by` and `user_id` are the same kind of ID. That is what lets the aggregate stop an officer from reviewing their own case. |

The five upload steps in `KycDocumentService`:
1. A read-only transaction checks that the caller owns the case, it is SUBMITTED, and it has room.
2. The bytes are checked: not empty, at most the maximum size, and a known format by magic bytes.
3. The SHA-256 is computed and the file is put to S3 under `kyc/{caseId}/{random UUID}`.
4. A second transaction locks the case, checks it again and inserts the metadata row.
5. If step 4 fails, the service deletes the S3 object, on a best-effort basis.

#### Shared and configuration

| File | Change | Why |
|---|---|---|
| `ErrorCode` | `KYC_DOCUMENTS_MISSING` (409), `CONCURRENT_MODIFICATION` (409), `DOCUMENT_TOO_LARGE` (413), `UNSUPPORTED_DOCUMENT_TYPE` (415), `STORAGE_UNAVAILABLE` (503) | Stable codes; each status says what happened. |
| `GlobalExceptionHandler` | `OptimisticLockingFailureException` → 409 `CONCURRENT_MODIFICATION`. A framework 413 (multipart limit) → `DOCUMENT_TOO_LARGE`. | A lost race is retryable, not a 500. |
| `application.yml` | `spring.servlet.multipart.max-file-size: 6MB` / `max-request-size: 7MB`; `trustledger.kyc.documents.*`; `trustledger.kyc.storage.*` (bucket from `TRUSTLEDGER_KYC_BUCKET`, region from `AWS_REGION`) | The container limit is just above 5 MB, so our exact check (and its ProblemDetail) decides, while a 100 MB upload is still cut off before it reaches memory. |
| `application-local.yml` | LocalStack endpoint, path-style, `test`/`test`, `create-bucket: true` | LocalStack's dummy credentials, not secrets, so no `.env` change was needed. |
| `infra/docker-compose.yml` | LocalStack `S3_SKIP_SIGNATURE_VALIDATION: "0"` | LocalStack accepts tampered and expired presigned URLs by default. With this set, local behaves like AWS. |
| Parent and core-app POM | AWS SDK BOM 2.56.0; `s3` (Apache, Apache 5 and Netty clients excluded) + `url-connection-client`; `testcontainers-localstack` (test) | ADR 0011. |
| `docs/adr/0011-aws-sdk-s3-document-storage.md`, `CLAUDE.md` stack line, `docs/SPEC.md` §4/§5 | New ADR; the `kyc_documents` row, the new detail endpoint and the new error codes | Docs match the code. |
| `http/kyc.http`, `http/samples/fake-pan.png`, `fake-address.pdf` | Full flow for customer1, officer1 and officer2 | Manual smoke test, using fake files only. |

#### Tests

| File | What it proves |
|---|---|
| `DocumentFormatTest` (21) | Each signature at its exact bytes is recognised. Everything else is refused: a renamed `.exe` (`MZ`), GIF, ZIP, ELF, HTML, a PDF signature off by one byte, every truncated signature, `%PDF-` not at offset 0, empty or null input. |
| `KycDocumentTest` (7), `KycCaseDocumentsTest` (6) | 5 MB is accepted, 5 MB + 1 and 0 bytes are refused, and only lower-case 64-hex hashes pass. Only FULL+SUBMITTED accepts documents (VERIFIED, APPROVED, REJECTED and MIN all give 409). Ownership is checked. |
| `KycDocumentServiceTest` (9, mocks) | The order is check → S3 → record. S3 is **never** called for a foreign case, an empty file, an oversize file or a wrong type. Exactly the limit passes; one byte more is 413. If the DB step fails, the **S3 object is deleted**. A failing cleanup does not hide the original error. If S3 fails, nothing is recorded. The SHA-256 matches the NIST "abc" vector. |
| `KycReviewServiceTest` (11, mocks) | Verify without every required type gives 409, naming the missing ones, and nothing changes. Approve publishes `KycLevelUpgraded(FULL)`; verify and reject publish nothing. Same officer → 409 with the state unchanged. Approve before verify → 409. A document link uses the 5-minute TTL; a document of another case is 404 and nothing is signed. |
| `KycDocumentPropertiesTest` (5), `KycStoragePropertiesTest` (4), `ErrorCodeTest` (+10) | Startup fails above 5 MB, above 5 minutes, with no required types, or with a half-set credential pair. `toString()` never shows credentials. |
| `KycDocumentMigrationIT` (7, raw SQL) | The database refuses >5 MB, empty files, `.exe`/GIF content types, unknown document types, upper-case or short hashes, a missing case (FK) and a duplicate S3 key. |
| `S3DocumentStorageIT` (5, LocalStack) | The object is stored with **SSE AES256**, the right content type and the exact bytes. A **wrong SHA-256 is refused by S3** and nothing is stored. Delete works, and deleting a missing key is fine. The presigned link has `X-Amz-Expires=300`, downloads the exact bytes, and sends `Content-Disposition: attachment`. **An expired link gives 403.** |
| `FullKycIT` (17) | **`POST /kyc/full`:** 201 SUBMITTED with the required types, and the address encrypted in the DB and absent from the response. Without MIN → 403. A duplicate → 409. A new case after a rejection → 201. A blank or 501-character address → 400. **Uploads:** PNG, PDF and JPEG are each stored, and the S3 bytes and the DB SHA match the response even when the client sends `text/plain`. Exactly 5 MB → 201; 5 MB + 1 → 413. A `.exe` sent as `application/pdf` → 415. An empty file → 400; an unknown type or missing file → 400. Another user's case or an unknown case → **404** (BOLA). A verified case → 409. An 11th document → 422 `LIMIT_EXCEEDED`. An officer → 403; no token → 401. Every refusal **leaves no row and no S3 object**. |
| `ComplianceKycIT` (16) | A verifies and B approves: 200, 200, `/kyc/me` shows FULL, and the DB has both officers. **The same officer → 409 `MAKER_CHECKER_VIOLATION`, still VERIFIED, `approved_by` NULL.** An officer with both roles can't verify their own case (409). Approve before verify → 409. Verify gives 409 until **both** required documents exist. Anything after approval → 409. An unknown case → 404. **Two officers approving at the same moment → exactly one 200 and one 409.** Reject without, blank or overlong reason → 400; with a reason → 200 and the customer sees it; the checker can also reject a VERIFIED case. The queue is filtered by status, oldest first, has no `address`, and validates `size`/`page`/`status`. The detail view shows the address and documents with hashes. **The presigned link** is `no-store`, `X-Amz-Expires=300`, `expiresAt` ≈ now + 5 min, downloads the exact bytes as an attachment, and **a tampered key gives 403**. A document of another case → 404. Customers → 403 on compliance endpoints. |
| `KycLevelUpgradedScenarioIT` (+2) | The Modulith `Scenario` sees `KycLevelUpgraded(userId, FULL)` after approval. A refused same-officer approval, verify and reject publish no FULL event. |
| `SecurityMatrixIT` (+28) | `/kyc/full`, `/kyc/cases/{id}/documents` and all six compliance endpoints across NONE, CUSTOMER, OFFICER, ADMIN and AUDITOR. |

**Fixes found while testing**

- **The test bucket was never created.** I first set `create-bucket=true` through the test
  `DynamicPropertyRegistrar`. But `@ConditionalOnProperty` is evaluated while bean definitions are read,
  before the registrar beans exist, so the bucket initializer was silently skipped and every upload got
  `NoSuchBucket`. **Fix:** static values (`bucket`, `path-style`, `create-bucket`) go in
  `src/test/resources/config/application.yml`. Only the container's dynamic values (endpoint, region,
  credentials) stay in the registrar.
- **A tampered or expired presigned URL still worked in LocalStack** (tests expected 403 and got 404 and 200).
  LocalStack skips presigned-URL signature validation by default. **Fix:** `S3_SKIP_SIGNATURE_VALIDATION=0`
  in Testcontainers and compose, so the tests prove what AWS does.
- **AWS SDK 2.56 switched its default HTTP client to Apache 5.** Excluding only `apache-client` was not
  enough; `apache5-client` is excluded too (checked with `dependency:tree`).
- **Mockito re-stubbing:** `when(mock.record(...)).thenThrow(...)` *calls* `record`, which ran the earlier
  `thenAnswer` with null arguments. Fixed with `doThrow(...).when(mock).record(...)`.

---

### 3. Machine setup done on Day 5 Session B (not in Git)

- **Branch** `feature/m3-kyc-full` created from `master`, committed and merged back into `master` (`--no-ff`). Not pushed.
- New Docker image pulled by Testcontainers: `localstack/localstack:4.14` (same tag as compose).
- No `.env` changes. On AWS (M14), set `TRUSTLEDGER_KYC_BUCKET` and `AWS_REGION`; credentials come from the task role.
- If LocalStack was already running from compose, recreate it once so the new environment variable applies.

### 4. Deferred

- **Audit events** for document views and KYC decisions: M10 (audit module). For now an INFO log line records
  the officer, case and document IDs, with no personal data.
- **KYC decision e-mail** (FR-N-1) on approve and reject: notification module.
- **Wallet tier upgrade** on `KycLevelUpgraded(FULL)` (FR-W-1): the M4 listener.
- **Orphan sweep:** if both the DB step and the S3 delete fail, an object can be left behind. Plan an S3
  lifecycle rule or a nightly sweep against `kyc_documents.s3_key` (M14).
- **Malware scanning** of uploads (for example an S3 event → scanner) is outside the M3 scope. Magic bytes stop
  type spoofing, not malicious PDFs; `Content-Disposition: attachment` limits the damage in the meantime.
- **PAN uniqueness across accounts** is still open from Session A.
- **SSE-KMS** instead of SSE-S3 when the AWS account exists (M14). LocalStack already runs `kms`.

---

### 5. What to learn from Day 5 Session B

1. **Never trust what the client says a file is.** The file name and `Content-Type` are just strings the
   client chose. The magic bytes are the file. Store the *detected* type, and serve downloads with
   `Content-Disposition: attachment` so a browser never renders an uploaded file in your origin.
2. **Keep remote calls out of transactions, and design the failure path.** The upload runs: short transaction,
   then S3, then short transaction. Nothing holds a row lock or a DB connection while bytes travel to S3.
   The price is two systems that can disagree, so you write the compensation (delete the object if the insert
   fails) and test it. "Two writes, no distributed transaction" always needs a clean-up story.
3. **Check, act, re-check under a lock.** The first check gives a fast, friendly error. The second check, under
   `SELECT ... FOR UPDATE`, is the one that counts: between them an officer may have verified the case, or a
   parallel upload may have taken the last slot. The same lock means two checkers approving at once can't both
   win; the second sees APPROVED and gets 409.
4. **Let the storage verify your hash.** Sending `x-amz-checksum-sha256` makes S3 recompute the hash and refuse
   corrupted bytes. The hash in the database is then a promise S3 has checked, not just a number.
5. **A presigned URL is a password with an expiry date.** Keep it short-lived (5 minutes, enforced at startup),
   send it with `Cache-Control: no-store`, keep it out of logs (`DocumentLinkView.toString()`), and log *who*
   asked for it. The signature covers the key and the expiry, which is why the tampered-key test gets 403.
6. **Make your fakes as strict as production.** LocalStack accepted tampered and expired links until one setting
   changed. A green test against a lenient fake proves nothing. Write the negative test first; if it doesn't
   fail when it should, your environment is lying to you.
7. **Know when configuration is read.** `@ConditionalOnProperty` is decided while bean definitions are read,
   before any bean (including a `DynamicPropertyRegistrar`) exists. Values that switch beans on or off belong in
   property files; only values that come from a running container belong in the registrar.
8. **404, not 403, for someone else's resource.** A 403 tells an attacker that case ID exists. Returning the
   same 404 for "not yours" and "not there" gives them nothing to enumerate.
