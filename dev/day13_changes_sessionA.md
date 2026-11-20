# Changes

## DAY13 Session A: Milestone M7 part 3a: Razorpay webhook intake (`POST /webhooks/razorpay`, `payments.webhook_events`)

**Goal:** Razorpay can tell us about payments safely. The parts:
- **`payments.webhook_events`** (V14): every delivery is stored, accepted or rejected.
- **`POST /api/v1/webhooks/razorpay`** (public, no JWT): reads the **raw body**, checks `X-Razorpay-Signature` = HMAC-SHA256(body, `RAZORPAY_WEBHOOK_SECRET`) with `MessageDigest.isEqual`.
  - Invalid signature → 400 `WEBHOOK_SIGNATURE_INVALID`, stored as invalid, not processed.
  - Duplicate event ID → 200, not processed again.
  - New valid event → `WebhookProcessor` runs it in **its own transaction**.
  - Unknown types → stored and ignored (200).
- **`WebhookEventHandler`**: the extension point for each event type. The real `payment.captured` / `payment.failed` handlers (credit the wallet, ledger, ADR 0006) come in part 3b.

Branch: `feature/m7-webhooks-part3a` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **Only intake, processor and dispatch this session (A).** `payment.captured` / `payment.failed` have no handler yet, so they're stored and ignored (marked processed). An event processed today won't be replayed later. That's fine, because no live Razorpay webhook should point at the app until part 3b.
2. **Partial unique index (B)**: `UNIQUE (gateway_event_id) WHERE signature_valid`, not a plain unique constraint. With a plain one, an attacker could post a forged delivery carrying a real event's ID first. The forged row (stored as invalid) would take the ID, and Razorpay's genuine delivery would then look like a duplicate and be dropped, so the wallet would never be credited. Now rejected rows can't block anything. SPEC §4 is updated.
3. **Payload stored without personal fields (C)**: `email`, `contact`, `vpa`, `card`, `bank_account`, `customer_details`, `billing_address`, `shipping_address` are removed at any depth before storing. For a rejected delivery, the payload is stored only if it is a JSON object, otherwise null.
4. **Flooding (D)**: a body-size cap only (64 KB, 413, not stored). An IP-based rate limit for anonymous requests is deferred (section 4).

Two consequences of how Razorpay works, which shaped the design:
- **The event ID is in the `X-Razorpay-Event-Id` header, not the body.** The body has `event` (the type), `payload` and `created_at`.
- **"Duplicate" means "already processed", not "already stored".** If the handler fails, we answer 500 and the row stays unprocessed. Razorpay's retry is then processed, not dropped as a duplicate.

---

### 1. How to verify Day 13 Session A

Run these from `dev/`. Start **Docker Desktop** first. One command per cell.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=RazorpaySignatureVerifierTest,WebhookEventTest,WebhookPayloadsTest,WebhookServiceTest,WebhookProcessorTest,PaymentsPropertiesTest,ErrorCodeTest"` | Signature checking against an independent openssl value, the record's rules, personal-field removal, call order and rejections, dispatch and duplicates, config validation. No Docker needed. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=RazorpayWebhookControllerIT,WebhookEventsMigrationIT,SecurityMatrixIT"` | End to end against Postgres: valid, duplicate, unknown type, invalid/missing signature, re-formatted JSON, missing event ID, **forged-then-genuine**, 413, **processing failure then retry**, **10 concurrent deliveries → processed once**; every V14 check. |
| 4 | Manual (optional): sign a body yourself and post it with curl (below) | Shows the raw-body signature with your own eyes. Needs the app running with the `local` profile. |

Manual check in Git Bash, with the app running (`./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"`):

```bash
set -a; source .env; set +a
BODY='{"event":"refund.processed","payload":{}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$RAZORPAY_WEBHOOK_SECRET" | awk '{print $NF}')
curl -i -X POST http://localhost:8080/api/v1/webhooks/razorpay -H 'Content-Type: application/json' \
  -H "X-Razorpay-Event-Id: evt_manual_$(date +%s)" -H "X-Razorpay-Signature: $SIG" --data-raw "$BODY"   # 200
curl -i -X POST http://localhost:8080/api/v1/webhooks/razorpay -H 'Content-Type: application/json' \
  -H "X-Razorpay-Event-Id: evt_manual_bad" -H "X-Razorpay-Signature: $SIG" --data-raw "$BODY "        # 400 (one extra space)
```

**Results:** `./mvnw clean verify` is **BUILD SUCCESS** (3 min 30 s): **916 unit tests** (+71) and **567 integration tests** (+20), all green. Details in section 5.

---

### 2. What changed and why

#### Database

| File | What |
|---|---|
| `V14__payments_create_webhook_events.sql` | `payments.webhook_events`: `id`, `gateway_event_id` (varchar 64), `event_type` (varchar 64), `payload` (jsonb), `signature_valid`, `received_at` (added; not in the SPEC table), `processed_at`. **Checks:** an accepted row has an event ID, a type and a payload (`ck_webhook_events_valid_complete`); a rejected row is never processed (`ck_webhook_events_processed_valid`). **Partial unique index** `ux_webhook_events_gateway_event` on `gateway_event_id WHERE signature_valid`. No foreign keys. |

#### Payments module: domain

| File | What |
|---|---|
| `WebhookEvent.java` | A record (stored with plain SQL, like idempotency keys). `accepted(...)`, `rejected(...)`, `markProcessed(now)`, which is refused (`INVALID_STATE_TRANSITION`) on a rejected or already-processed event. The constructor enforces the same rules as the database checks. `toString` never prints the payload. |
| `WebhookEventRepository.java` | Port: `insertIfAbsent` (accepted), `insertRejected`, `findAcceptedForUpdate`, `markProcessed`. |
| `WebhookSignatureVerifier.java` | Port: `isValid(rawBody, signature)`. Never throws on bad input. |

#### Payments module: application

| File | What |
|---|---|
| `WebhookService.java` | `receive(rawBody, signature, eventId)` with **`@Transactional(propagation = NEVER)`**. (1) Bad signature → store rejected, throw `WEBHOOK_SIGNATURE_INVALID`. (2) Signed but no valid event ID, JSON object or type → store rejected, `VALIDATION_FAILED`. (3) `WebhookWriter.storeAccepted` commits. (4) `WebhookProcessor.process`. The event ID must match `[A-Za-z0-9_-]{1,64}`, otherwise it isn't stored or logged, so log injection isn't possible. Logs the ID and type only, never the body. |
| `WebhookWriter.java` | The short insert transactions (a separate bean so they go through the proxy). |
| `WebhookProcessor.java` | **`@Transactional(propagation = REQUIRES_NEW)`**. Locks the row (`FOR UPDATE`); already processed → `DUPLICATE`; runs the handler for the type (none → `IGNORED`); sets `processed_at`, which commits **together with** the handler's work. Two handlers for one type fail startup. |
| `WebhookEventHandler.java` | `eventType()` + `handle(event)`. Part 3b adds the `payment.captured` / `payment.failed` handlers here. |
| `WebhookOutcome.java` | `PROCESSED`, `IGNORED`, `DUPLICATE` (all 200). |
| `WebhookPayloads.java` | Parses the body **for storage only**: reads `event` (strict dotted lower-case pattern), removes the personal fields at any depth, and refuses `\u0000` (Postgres jsonb can't store it). The signature is always checked over the raw bytes, never this re-serialised JSON. |
| `PaymentsProperties.java` | `+ webhooks { max-body-size }`, 1 KB to 1 MB, default 64 KB. |

#### Payments module: infrastructure and web

| File | What |
|---|---|
| `RazorpaySignatureVerifier.java` | HMAC-SHA256 with the webhook secret from `RazorpayProperties` (which stays inside infrastructure). The header must be exactly 64 hex characters, upper or lower case, and is decoded with `HexFormat`. Compared with **`MessageDigest.isEqual`**. A new `Mac` per call (`Mac` isn't thread-safe). |
| `JdbcWebhookEventRepository.java` | `insert … on conflict (gateway_event_id) where signature_valid do nothing`. The `where` repeats the index predicate, which is how Postgres picks a partial index as the conflict target. Plus `select … for update` and a guarded `update`. |
| `RazorpayWebhookController.java` | `POST /webhooks/razorpay`, version 1. Reads the body **from the request stream** (no `@RequestBody`, no Jackson), at most limit + 1 bytes; `Content-Length` over the limit is refused before reading. Returns 200 with an empty body. `@SecurityRequirements` marks it public in OpenAPI. |

#### Shared, config, docs

| File | What |
|---|---|
| `ErrorCode.java` | `+ WEBHOOK_SIGNATURE_INVALID (400)`, `+ PAYLOAD_TOO_LARGE (413)`. |
| `application.yml` | `trustledger.payments.webhooks.max-body-size: 64KB`. |
| `SecurityConfig.java` | **Unchanged.** `/api/*/webhooks/**` was already `permitAll` and CSRF is off. |
| `docs/SPEC.md` | §4 `webhook_events` row (partial index, `received_at`, redaction); §5 error table. |
| `payments/package-info.java` | Describes the webhook flow. |

#### Tests

| Test | Kind | Covers |
|---|---|---|
| `RazorpaySignatureVerifierTest` (16) | Unit | Matches an **independently computed openssl HMAC**; upper-case hex accepted; another secret, one changed body byte, **the same JSON with different spacing** and one changed signature character all fail; null, empty, blank, short, long, non-hex and `sha256=`-prefixed headers → `false`, no exception. |
| `WebhookEventTest` (8) | Unit | Factories, required fields when accepted, `markProcessed` once only and never on a rejected delivery, `toString` without the payload. |
| `WebhookPayloadsTest` (23) | Unit | Type read from a realistic `payment.captured` body; personal fields removed **at any depth** (a value `"card"` is kept); non-objects and truncated JSON → nothing; NUL → nothing; malformed types → null; real Razorpay types accepted. |
| `WebhookServiceTest` (10) | Unit, Mockito | Store → process order; payload stored without e-mail; invalid or missing signature → rejected row, never processed; garbage → malformed event ID not stored; no event ID or no type → `VALIDATION_FAILED`, stored; a processing failure passes through after the row was stored; `NEVER` and `REQUIRES_NEW` propagation. |
| `WebhookProcessorTest` (9) | Unit | Dispatch by type; lock → handle → mark order; unknown type `IGNORED` but marked; no handlers at all; `DUPLICATE` runs nothing; handler failure → not marked; missing row / row changed under the lock → `IllegalStateException`; two handlers for one type → startup failure. |
| `PaymentsPropertiesTest` (+2), `ErrorCodeTest` (+2 rows) | Unit | Webhook body limit 1 KB to 1 MB, required, binds from config, 2 MB fails startup; the two new codes' statuses. |
| `WebhookEventsMigrationIT` (6) | Integration | Every V14 check with raw SQL; two accepted rows with one ID refused; **rejected rows with that ID don't block the accepted one**; payload is jsonb. |
| `RazorpayWebhookControllerIT` (12) | Integration | See section 1, row 3. Uses `RecordingWebhookHandler`, a test-only handler for the made-up type `test.recorded`, to count real processing. Requests are signed in the test with `javax.crypto`, independently of the production verifier. |
| `SecurityMatrixIT` (+2 rows) | Integration | Webhook reachable with no token and with a customer token (both 400: no signature). |

---

### 3. Machine setup for Day 13 Session A (not in Git)

- Nothing new. `RAZORPAY_WEBHOOK_SECRET` in `.env` must be non-blank (already needed since Day 12). **Pick it now and keep it**: in part 3b you register the webhook in the Razorpay test dashboard with exactly this secret.
- To receive real test-mode webhooks locally (part 3b), Razorpay needs a public HTTPS URL that forwards to `localhost:8080` (for example a tunnel). Not needed for this session's tests.

### 4. Deferred

- **M7 part 3b:** `payment.captured` handler (check limits again, ledger GATEWAY_CLEARING → WALLET, `Topup.capture`, `TopupCaptured`), `payment.failed` handler, out-of-order events (`payment.failed` then `payment.captured` on the same order), **ADR 0006** (refund vs suspense for over-limit captures), and the **`PendingTopupReconciler`**.
- **Rate limit for anonymous webhook calls:** the endpoint stores every delivery, so a flood of junk grows the table. Today only the 64 KB cap bounds a single request. Options: a per-IP Bucket4j bucket for `/webhooks/**`, or an allowlist of Razorpay's published webhook IP ranges at the edge (ALB/WAF in M12).
- **Retention:** rejected rows are evidence but don't need to live forever. A cleanup job (for example 90 days) fits with the privacy work (M10).
- **A stray `Authorization` header on a webhook request** is expected to get 401 from the resource server before the controller runs (not covered by a test). Razorpay never sends one, so this is harmless; ignoring bearer tokens on `/webhooks/**` would need a custom `BearerTokenResolver`.
- **`gateway_event_id` stays unique only among accepted rows.** If Razorpay ever re-sent an event under a *new* ID, it would be processed twice. The part 3b handlers must therefore be idempotent on their own (the top-up state machine plus the ledger's unique `(reference_type, reference_id, entry_type)` already ensure this).

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS** (total 3 min 30 s; core-app 3 min 02 s): core-app **916 unit / 567 integration** tests, risk-aml-service green, `ModularityTests` and ArchUnit green.

---

### What to learn from this

- **Sign bytes, not objects.** An HMAC covers bytes. The moment a framework parses JSON and writes it back, key order, spacing or number format can change and the signature breaks, or worse, you end up verifying something other than what was sent. So the controller reads the stream itself.
- **Constant-time comparison.** `String.equals` returns at the first differing character, so response time leaks how many leading characters were right. `MessageDigest.isEqual` always compares every byte.
- **A uniqueness rule is a security decision.** "Event ID is unique" sounds harmless, but once untrusted rows share that column, it lets an attacker block the genuine event. Scope the rule to trusted rows with a partial index.
- **De-duplicate on the outcome, not on arrival.** Store first (commit), then process in a separate transaction, and mark "processed" in the *same* transaction as the work. A crash or exception then leaves the event retryable rather than lost, and the row lock (`FOR UPDATE`) makes concurrent redeliveries wait instead of racing.
