# Changes

## DAY13 Session B: Milestone M7 part 3b: settling top-ups (`payment.captured` / `payment.failed`, ADR 0006, `PendingTopupReconciler`)

**Goal:** money paid at Razorpay reaches the wallet, exactly once, and nothing captured is ever lost. The parts:
- **`payment.captured` handler**:
  - lock the top-up, then the wallet;
  - re-check the limits;
  - post GATEWAY_CLEARING → WALLET, credit the wallet, mark the top-up CAPTURED and publish **`TopupCaptured`**.
- **`payment.failed` handler**: PENDING → FAILED. Any other status is ignored.
- **Over-limit captures (ADR 0006)**: the money is **parked** in `PLATFORM:SUSPENSE` with the new status **PARKED**. There is no wallet credit and no event.
- **`PendingTopupReconciler`**: every 5 minutes, PENDING top-ups older than 15 minutes are looked up with `fetchOrderPayments`.
  - Captured → credited (or parked).
  - Nothing captured after 24 hours → EXPIRED.

Branch: `feature/m7-webhooks-part3b` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **Over-limit captures go to suspense (option B), not refunded.** The full reasoning is in `docs/adr/0006-over-limit-topups.md`:
   - no remote call inside the money transaction;
   - Razorpay refunds are not idempotent for us;
   - the SUSPENSE account already exists for this.
   An operator settles PARKED rows by hand for now.
2. **One settlement service for every path.** The webhook handlers and the reconciler all call `TopupSettlementService`, so a webhook and the reconciler racing on the same top-up credit it once.
3. **Late capture is credited.** A captured payment also settles a FAILED or EXPIRED top-up, not only a PENDING one. This covers two real cases:
   - in Razorpay Checkout the customer can retry on the **same order** after a failed attempt;
   - a payment can be captured after our 24-hour expiry.
   Dropping either would lose real money.
4. **Out-of-state events are ignored, never thrown.** If a webhook throws, Razorpay retries it for a day and it fails every time. So these are logged and the event is marked processed:
   - an unknown order;
   - an already-settled top-up (same payment: INFO; a different payment: ERROR, "needs manual review");
   - an amount mismatch;
   - `payment.failed` after the capture.

---

### 1. How to verify Day 13 Session B

Run these from `dev/`. Start **Docker Desktop** first. One command per cell.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=TopupTest,TopupLimitCheckerTest,WebhookPaymentPayloadTest,TopupSettlementServiceTest,PaymentWebhookHandlersTest,TopupReconciliationServiceTest,TopupReconcilerPropertiesTest"` | State machine (PARKED, late capture), the capture-time limit re-check, reading the payment from a stored event, credit / park / ignore rules, handlers, the reconciler's decisions, and config validation. No Docker needed. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=TopupWebhookSettlementIT,PendingTopupReconcilerIT,TopupsMigrationIT"` | End to end against Postgres and WireMock (see the list below), plus every V15 check. |
| 4 | Manual (optional): a real test-mode payment through `checkout.html` with the webhook pointing at your tunnel (below) | Shows the whole flow with Razorpay itself. |

Row 3 covers:
- `TopupWebhookSettlementIT`:
  - capture → wallet and ledger credited;
  - duplicates (same event; same payment under another event ID) → credited once;
  - over the cap or frozen wallet → PARKED in suspense;
  - failed then captured → credited; captured then failed → stays CAPTURED;
  - capture after expiry → credited.
- `PendingTopupReconcilerIT`:
  - captured at the gateway → credited, or parked when over a limit;
  - nothing paid yet → stays PENDING;
  - over 24 hours → EXPIRED;
  - younger than 15 minutes → not looked up;
  - HTTP 500 → skipped;
  - **webhook and reconciler racing on one top-up → one credit** (5 rounds).

Manual check (optional), with the app running (`./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"`):
1. Make `localhost:8080` reachable over public HTTPS, for example with a tunnel.
2. In the Razorpay **test** dashboard, open Settings → Webhooks. Add `https://<tunnel>/api/v1/webhooks/razorpay`, set the secret to **exactly** `RAZORPAY_WEBHOOK_SECRET` from `.env`, and tick `payment.captured` and `payment.failed`.
3. Open `http://localhost:8080/checkout.html`, create a ₹100 top-up and pay with a Razorpay test card or UPI.
4. `GET /api/v1/topups/{id}` → CAPTURED; `GET /api/v1/wallets/me` → balance +₹100.
5. Stop the tunnel and pay another top-up. About 15–20 minutes later the reconciler credits it (log line: `Pending top-up reconciliation: ...`).

**Results:** `./mvnw clean verify` is **BUILD SUCCESS** (3 min 32 s): **992 unit tests** (+76) and **590 integration tests** (+23), all green. Details in section 5.

---

### 2. What changed and why

#### Database

| File | What |
|---|---|
| `V15__payments_topups_settlement.sql` | Replaces four V13 checks. `ck_topups_status` + **PARKED**. `ck_topups_order_id`: PARKED needs an order. `ck_topups_journal_entry` / `ck_topups_payment_id`: a journal entry and payment ID **exactly when CAPTURED or PARKED**. New **partial index** `ix_topups_pending_created ON (created_at) WHERE status = 'PENDING'` for the reconciler. |

#### Payments module: domain

| File | What |
|---|---|
| `TopupStatus.java` | `+ PARKED`. New sets: `SETTLEABLE` (PENDING, FAILED, EXPIRED) and `SETTLED` (CAPTURED, PARKED). `OPEN` and `COUNTED_FOR_LOAD` are unchanged: PARKED money is not on the wallet and doesn't use up the monthly load. |
| `Topup.java` | `capture(...)` now also works from FAILED / EXPIRED, as long as the top-up has an order (late capture). New `park(paymentId, entryId, now)`, `isSettleable()`, `isSettled()`. A FAILED top-up without an order (the order call itself failed) can never be settled. |
| `TopupRepository.java` | `+ findByGatewayOrderIdForUpdate`, `+ findByIdForUpdate` (`PESSIMISTIC_WRITE`), `+ findPendingCreatedBefore(before, limit)`. |

#### Payments module: application

| File | What |
|---|---|
| `TopupSettlementService.java` | **The only place top-up money is booked.** `capture(payment)`:<br>1. lock the top-up by order ID, then the wallet;<br>2. ignore an unknown order, a settled top-up or an amount mismatch;<br>3. `checkCapture`;<br>4. credit: GATEWAY_CLEARING → `WALLET:<id>`, credit, CAPTURED, `TopupCaptured`; or park: GATEWAY_CLEARING → SUSPENSE, PARKED.<br>`fail(payment)`: PENDING only. `expire(topupId)`: PENDING only. `duePending(...)`: a read-only transaction that returns plain records. Both postings are `TOPUP` entries referenced as (`TOPUP`, top-up ID), so the ledger refuses a second booking either way. |
| `TopupLimitChecker.java` | `+ checkCapture(wallet, limits, amount)` → `Optional<ParkReason>`:<br>- wallet not ACTIVE;<br>- balance + amount > max balance;<br>- this month's **CAPTURED** + amount > monthly load.<br>Open top-ups are **not** counted: they reserved their own room, and the one being captured is itself open. |
| `ParkReason.java`, `SettlementOutcome.java` | `WALLET_NOT_ACTIVE / MAX_BALANCE / MONTHLY_LOAD`; `CAPTURED / PARKED / FAILED / EXPIRED / IGNORED`. |
| `PaymentCapturedHandler.java`, `PaymentFailedHandler.java` | `WebhookEventHandler`s for `payment.captured` / `payment.failed`. They run in `WebhookProcessor`'s transaction, so the credit and `processed_at` commit together. An unreadable payment is logged and the event marked processed. |
| `WebhookPayloads.java` | `+ payment(json)`: reads `payload.payment.entity` (`id`, `order_id`, integer `amount`, `currency`, `status`, `error_code`) from the stored, already-redacted JSON. IDs must match the gateway patterns. Only payments made on an order are returned. |
| `TopupReconciliationService.java` | **`@Transactional(propagation = NEVER)`** `reconcile()`:<br>1. read the oldest due PENDING top-ups (batch);<br>2. for each, `fetchOrderPayments` with no transaction open;<br>3. a captured payment → `capture`; nothing captured and older than 24 hours → `expire`; otherwise leave it.<br>Gateway errors skip that one top-up; a settlement exception is logged, counted, and the run continues. Returns a `TopupReconciliationReport`. |
| `PendingTopupReconciler.java` | `@Scheduled(cron = trustledger.payments.reconciler.cron)` + ShedLock `payments-topup-reconciler`; `runLocked()` for tests. |
| `TopupReconcilerProperties.java` | `trustledger.payments.reconciler.*`: `cron`, `pending-after` (≥ 1 m), `expire-after` (> pending-after), `batch-size` (1–1,000), `lock-at-most-for` (> 0). A separate record, so `PaymentsProperties` and its test callers are unchanged. |
| `PaymentsConfig.java` | Registers `TopupReconcilerProperties`. |

#### Payments module: public API

| File | What |
|---|---|
| `TopupCaptured.java` | `(topupId, walletId, amount, journalEntryId, capturedAt)`: IDs and amount only. Published inside the crediting transaction; AML consumes it from M8 / M9. Not published for PARKED. |

#### Config, docs

| File | What |
|---|---|
| `application.yml` | `trustledger.payments.reconciler`: `"0 */5 * * * *"`, 15m, 24h, 100, 10m. |
| `docs/adr/0006-over-limit-topups.md` | **New.** Context (why limits can be broken at capture time), options (refund, suspense, credit anyway, throw), decision, how an operator settles a PARKED row, follow-ups. |
| `docs/SPEC.md` | §4 `topups` row (PARKED, late capture, index); a posting-rules row for the suspense entry; §8 M7 reconciler note. |
| `payments/package-info.java` | Describes settlement and the reconciler; `TopupCaptured` added to the public API. |

#### Tests

| Test | Kind | Covers |
|---|---|---|
| `TopupTest` (38, +12) | Unit | PARKED transitions, late capture / park from FAILED and EXPIRED, a FAILED top-up without an order can't settle, settled top-ups can't be captured / parked / failed, the new sets. |
| `TopupLimitCheckerTest` (15, +6) | Unit | `checkCapture`: exactly on both limits OK, over the cap, over the monthly load, open top-ups ignored, frozen / closed, the Indian month boundary. |
| `WebhookPaymentPayloadTest` (21) | Unit | Valid captured / failed payments, error code kept only if it is a code, unknown status → UNKNOWN, 15 malformed shapes → empty, non-payment events, reads what `parse` stored. |
| `TopupSettlementServiceTest` (16) | Unit, Mockito | Credit (posting lines, credit, event); **lock order** top-up → wallet → limits → post → credit; park (SUSPENSE lines, no credit, no event); frozen; late capture from FAILED / EXPIRED; same payment again; second payment; unknown order; amount mismatch; fail / expire rules. |
| `PaymentWebhookHandlersTest` (6) | Unit | Event types; captured / failed dispatched with the parsed payment; non-captured or unreadable payloads ignored. |
| `TopupReconciliationServiceTest` (9) | Unit | Captured → capture (and the order ID filled in when missing); young → left; over 24 hours → expire; unavailable and rejected lookups skipped and **never expired**; a settlement exception counted and the run continues; nothing due → no gateway calls; `NEVER` propagation. |
| `TopupReconcilerPropertiesTest` (6) | Unit | Every validation rule. |
| `TopupsMigrationIT` (10, +4) | Integration | PARKED accepted; PARKED needs an order, payment and entry; only CAPTURED / PARKED carry them; the partial index exists. |
| `TopupWebhookSettlementIT` (12) | Integration | See section 1, row 3. |
| `PendingTopupReconcilerIT` (7) | Integration | See section 1, row 3. WireMock stubs `GET /v1/orders/{id}/payments`; old PENDING rows left by other IT classes are expired first, so the batch is this test's own. |
| `RazorpayWebhooks` (helper), `PaymentsFixtures` (+3 helpers) | Test support | Signed `payment.*` deliveries; journal entry count and lines for a top-up; payment ID. |

---

### 3. Machine setup for Day 13 Session B (not in Git)

- Nothing new for the tests.
- For the manual check: a public HTTPS tunnel to `localhost:8080`, and a webhook in the Razorpay **test** dashboard with the same secret as `RAZORPAY_WEBHOOK_SECRET`. **Test mode only; never live keys.**

### 4. Deferred

- **Settling PARKED top-ups:** an admin endpoint (maker-checker) that either credits the wallet (Dr SUSPENSE / Cr WALLET) or records a refund (Dr SUSPENSE / Cr GATEWAY_CLEARING). Later, an automatic refund job that drains PARKED rows (ADR 0006, option 1).
- **Metric / alert on PARKED top-ups and the SUSPENSE balance** (M12).
- **FAILED top-ups whose retried payment was captured but whose webhook was lost:** the reconciler only looks at PENDING. Razorpay retries webhooks for 24 hours, so this is unlikely; it could also scan FAILED-with-order rows younger than 24 hours.
- **A second captured payment on an already-settled order** (should not happen: a Razorpay order is paid once): logged at ERROR for manual review, not booked.
- **A captured amount that differs from the top-up:** logged at ERROR, not booked, and the top-up stays PENDING. The reconciler will hit the same mismatch on every run until someone looks at it.
- **`TopupCaptured` → Kafka `TopupCapturedV1`** (M8) and the AML consumer (M9). There is no listener yet, so Modulith doesn't record it in `event_publication`; the ITs check it with `@RecordApplicationEvents`.
- From Session A, still open: an IP rate limit for anonymous webhook calls; retention of rejected webhook rows.

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS** (total 3 min 32 s): core-app **992 unit / 590 integration** tests, risk-aml-service green, `ModularityTests` and ArchUnit green.

---

### What to learn from this

- **Check limits twice: when the money is promised and when it arrives.** The first check (top-up creation) protects the customer from a refused payment. The second (capture) protects the platform, because the world changed in between: a freeze, a transfer in, a new month. The two checks are different on purpose: the second must not count the open top-up it is settling.
- **Captured money must always land somewhere.** You can't "reject" a payment the gateway already holds. Every branch either credits the wallet or books the money to suspense; ignoring the event, or throwing until Razorpay gives up, would leave the books short.
- **One code path, one lock order.** The webhook and the reconciler both go through `TopupSettlementService`, which locks the top-up row first and the wallet second. A race is then just "the second one waits, then sees CAPTURED", and the ledger's unique `(reference_type, reference_id, entry_type)` is the backstop.
- **Keep remote calls out of transactions by construction.** The reconciler reads in one short transaction, calls Razorpay with none open (`Propagation.NEVER` enforces it), then settles each top-up in its own transaction. A slow gateway can never hold a wallet lock.
- **An out-of-order event is normal input, not an error.** `payment.failed` after `payment.captured`, or a capture after expiry, are expected sequences. Model which transitions they may cause, not whether they "should" happen.
