# Changes

## DAY12 Session B: Milestone M7 part 2: top-up creation, `Topup` aggregate, `POST/GET /topups`, local checkout page

**Goal:** a customer can start adding money. The parts:
- **`payments.topups`** (V13) and the **`Topup` aggregate**: CREATED → PENDING → CAPTURED | FAILED | EXPIRED.
- **`POST /api/v1/topups`** (`@Idempotent`): check the monthly load and the balance cap, save CREATED, call `createOrder` **outside any transaction**, then save PENDING with the order ID. If the gateway fails, the top-up is marked FAILED and the API answers 503 `GATEWAY_UNAVAILABLE` (or 502 `GATEWAY_REJECTED` for a 4xx).
- **`GET /api/v1/topups/{id}`**: the owner sees the top-up; anyone else gets the same 404 as an unknown ID.
- **`checkout.html`** (local profile only) opens Razorpay Checkout in test mode.

Branch: `feature/m7-topups-part2` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **Open top-ups (CREATED, PENDING) count toward both limits.** Otherwise someone could open ten orders that each fit, pay them all, and end up in the over-limit refund path. The cost: an abandoned checkout keeps its share of the limit reserved until the reconciler expires it (part 3).
2. **The wallet row is locked while a top-up is created.** It's held for milliseconds and never across the gateway call. This is what makes concurrent creations deterministic.
3. **The checkout key ID comes through the port:** `PaymentGateway.checkoutKeyId()`. `RazorpayProperties` stays inside infrastructure (the Day 12 deferral).
4. **Minimum top-up ₹1.00, from config** (`trustledger.payments.topups.min-amount`). Razorpay refuses smaller orders, which would otherwise surface as a confusing 502.

---

### 1. How to verify Day 12 Session B

Run these from `dev/`. Start **Docker Desktop** first. One command per cell.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=TopupTest,TopupLimitCheckerTest,TopupServiceTest,PaymentsPropertiesTest,CheckoutPageLocalTest,ModularityTests"` | Aggregate state machine, limit rules (IST month, exactly-on-limit), call order and failure handling, `Propagation.NEVER`, config validation, checkout page security. No Docker needed. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=TopupControllerIT,TopupServiceIT,TopupsMigrationIT,TopupRepositoryIT"` | End to end against Postgres and WireMock: happy path, idempotency, every refusal, 503/502, open circuit, BOLA, no transaction during the gateway call, concurrency. |
| 4 | Start the app (`./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"`), open `http://localhost:8080/checkout.html`, paste a customer token (from `http/auth.http`), click **Create top-up and pay**, pay with a Razorpay **test** card or UPI `success@razorpay` | The manual test in Razorpay test mode. The top-up stays **PENDING**: the webhook that credits the wallet arrives in part 3. |
| 5 | `http/topups.http` | Requests for create, replay, someone else's top-up (404), over the limit (422), below the minimum, missing key. |

**Results:** `./mvnw clean verify` is **BUILD SUCCESS**: 845 unit tests and 547 integration tests, all green. Details in section 5.

---

### 2. What changed and why

#### Database

| File | What |
|---|---|
| `V13__payments_create_topups.sql` | `payments.topups`: `id`, `wallet_id` (no FK across schemas), `amount_minor` (> 0), `currency` (`INR`), `gateway_order_id`, `gateway_payment_id`, `status`, `journal_entry_id`, `created_at`, `updated_at`, `version`. **Checks:** CREATED has no order ID; PENDING/CAPTURED/EXPIRED must have one (FAILED may or may not: the order call failed, or the payment failed later); `journal_entry_id` and `gateway_payment_id` set **exactly when CAPTURED**. **Unique:** order ID, payment ID, journal entry. **Index** `(wallet_id, created_at)` for the monthly sum. |

#### Payments module: domain

| File | What |
|---|---|
| `Topup.java` | The aggregate. `create` (positive INR), `markPending(orderId)` (CREATED → PENDING), `fail` (CREATED or PENDING → FAILED), `capture(paymentId, journalEntryId)` (PENDING → CAPTURED), `expire` (PENDING → EXPIRED), `belongsTo(walletId)`. Anything else is `INVALID_STATE_TRANSITION`. Gateway IDs are checked with the port's patterns. `capture`/`expire` are ready for part 3 (webhook, reconciler). |
| `TopupStatus.java` | The five states, plus `OPEN` (CREATED, PENDING) and `COUNTED_FOR_LOAD` (CREATED, PENDING, CAPTURED). |
| `TopupRepository.java` | Port: `sumCreatedSince(wallet, from, statuses)` (monthly load), `sumByStatus(wallet, statuses)` (open top-ups for the balance cap). |
| `PaymentGateway.java` | `+ checkoutKeyId()`: the public key ID the browser needs for Checkout. |

#### Payments module: application

| File | What |
|---|---|
| `TopupService.java` | `create(userId, amount)` with **`@Transactional(propagation = NEVER)`**: (1) `TopupWriter.open` commits CREATED; (2) `gateway.createOrder(amount, topupId)` runs with **no transaction and no lock**; (3) `TopupWriter.markPending`. On `GatewayUnavailableException`/`GatewayRejectedException`: `markFailed` in its own transaction, then rethrow. If `markFailed` itself fails, its error is attached as *suppressed* and the gateway error is still what the client sees. |
| `TopupWriter.java` | The short transactions, in a separate bean so each method goes through the Spring proxy. `open`: minimum amount → wallet (`KYC_REQUIRED` if none) → **`lockForUpdate`** → active (`WALLET_FROZEN` / closed) → limits → `saveAndFlush`. |
| `TopupLimitChecker.java` | Monthly load: this month's (Asia/Kolkata) CREATED+PENDING+CAPTURED + amount ≤ `monthlyLoad`. Balance cap: balance + open top-ups + amount ≤ `maxBalance`. Exactly on a limit is allowed; one paisa over is `LIMIT_EXCEEDED`. Must be called under the wallet lock. |
| `TopupQueryService.java` | `get(caller, id)`: other people's top-ups give the same 404 as unknown IDs (BOLA). |
| `TopupResult.java` | What leaves the module (the entity never does). |
| `PaymentsProperties.java` | `+ topups { min-amount, checkout-page }`; a zero, missing or non-INR minimum fails startup. |

#### Payments module: infrastructure and web

| File | What |
|---|---|
| `JpaTopupRepository.java` | Spring Data with the two JPQL sums. |
| `RazorpayGatewayAdapter.java` | `checkoutKeyId()`, and a package-private `resetCircuit()` used only by tests (ITs share one adapter, so a test that opens the circuit closes it again). |
| `TopupController.java` | `POST /topups` (`@Idempotent`, CUSTOMER, 201) and `GET /topups/{id}`. Response: `id, status, amount, currency, gatewayOrderId, gatewayPaymentId, keyId, createdAt, updatedAt`; amounts as decimal strings. |
| `CheckoutPageConfig.java` | Only when `trustledger.payments.topups.checkout-page=true` (set only in `application-local.yml`). A resource handler for `/checkout.html` and `/checkout.js` from `classpath:/checkout/` (not `/static`, so no other profile serves them), and **its own security chain** (`@Order(2)`, these two paths only, public) with a CSP that allows `checkout.razorpay.com` scripts and Razorpay frames. The JS lives in its own file, so the page doesn't need `'unsafe-inline'` for scripts. |
| `PaymentsStubController.java` | **Deleted**: the 501 placeholder for `POST /topups` from M2. |

#### Resources, config, docs

| File | What |
|---|---|
| `resources/checkout/checkout.html`, `checkout.js` | Paste a token, enter an amount → `POST /topups` with `Idempotency-Key: crypto.randomUUID()` → `new Razorpay({key, order_id})` → on success shows the payment ID and polls `GET /topups/{id}`. |
| `application.yml` / `application-local.yml` | `trustledger.payments.topups.min-amount: "1.00"`, `checkout-page: false` (local: `true`). |
| `http/topups.http` | Manual requests (section 1). |
| `payments/package-info.java` | Describes the top-up flow. |

#### Tests

| Test | Kind | Covers |
|---|---|---|
| `TopupTest` (26) | Unit | Creation rules; every allowed transition; **every** refused transition (parameterised over all states) leaves the status unchanged; ID validation; ownership; the OPEN / COUNTED sets. |
| `TopupLimitCheckerTest` (9) | Unit | Exactly on the monthly load OK, one paisa over refused; open top-ups count toward the cap; FAILED/EXPIRED don't count; **month starts at midnight IST** (30 Nov 19:00 UTC is already December). |
| `TopupServiceTest` (6) | Unit, Mockito | Call order open → createOrder (receipt = top-up ID) → markPending; a refusal never reaches the gateway; unavailable and rejected both mark FAILED and rethrow the same exception; a failing `markFailed` doesn't hide the gateway error; `create` is `Propagation.NEVER`. |
| `PaymentsPropertiesTest` (+2), `RazorpayGatewayAdapterTest` (+1) | Unit | Minimum amount validation and binding; `checkoutKeyId()` sends no request. |
| `CheckoutPageLocalTest` (3) | `@WebMvcTest` slice | With the flag: page and script need no token, CSP allows Razorpay and no inline script; the API, other files and actuator still need a token. |
| `TopupsMigrationIT` (6) | Integration | Every V13 check and unique constraint with raw SQL; the index exists. |
| `TopupRepositoryIT` (3) | Integration | Mapping round trip; both sums filter by wallet, window boundary and status. |
| `TopupControllerIT` (23) | Integration, WireMock | Happy path (paise + receipt sent, PENDING stored, `keyId` returned, nothing credited); replay (1 order, same body); key reuse 422; missing key 400; 8 bad amounts 400; minimum accepted; monthly load (with open/captured/failed rows); FULL cap; MIN ₹10,000 cap; frozen 409; no wallet 403. **Every refusal: zero gateway requests and no row.** Gateway 500 → 503, FAILED, **same key retried succeeds** as a new top-up; timeout → 503 within the 3 s limit; 400 → 502; **10 failures open the circuit, the 11th gets 503 with no request** (deferred from Day 12). Owner 200; other customer, unknown ID and wallet-less user all 404 with the same detail. |
| `TopupServiceIT` (3) | Integration | **While the order request is in flight** (WireMock 1.5 s delay), the CREATED row is already committed and `SELECT … FOR UPDATE NOWAIT` on the wallet succeeds, so no lock is held. Calling `create` inside a transaction → `IllegalTransactionStateException`, no row, no request. **10 concurrent ₹2,000 top-ups on a MIN wallet → exactly 5 succeed, 5 `LIMIT_EXCEEDED`, 5 orders.** |
| `SecurityMatrixIT` (+7 rows), `RateLimitIT` | Integration | Top-up POST/GET roles; `/checkout.html` and `/checkout.js` denied without the local flag. The stub's 501 became 400 (no key), since these tests only check roles and rate-limit buckets. |

---

### 3. Machine setup for Day 12 Session B (not in Git)

- Nothing new. The `RAZORPAY_*` variables from Session A are needed for the local profile, and the key ID must be `rzp_test_…`.
- For the manual payment: Razorpay Dashboard in **Test Mode**. Test card `4111 1111 1111 1111`, any future expiry, any CVV; or UPI `success@razorpay`.

### 4. Deferred

- **M7 part 3:** the `POST /webhooks/razorpay` controller (HMAC over the raw body, `payments.webhook_events`, de-duplication), crediting the wallet on `payment.captured` (ledger GATEWAY_CLEARING → WALLET, `TopupCaptured`), the limit re-check at capture and **ADR 0006** (refund vs suspense for over-limit captures), and the **`PendingTopupReconciler`** (PENDING > 15 min, plus CREATED rows left behind if the process died between steps).
- **Out-of-order events:** Razorpay allows several payment attempts per order, so `payment.failed` followed by `payment.captured` is possible. Today `capture` is allowed from PENDING only. Part 3 decides whether FAILED → CAPTURED is needed, together with ADR 0006.
- **Monthly load uses the creation time.** A top-up created on 31 Jan at 23:59 and captured on 1 Feb counts toward January. Revisit if the regulator's rule turns out to be "by credit date".
- **The wallet lock-mode trap from Day 11:** relevant again for the credit on capture (part 3).

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS** (core-app 2 min 34 s).

- **Unit tests:** core-app **845/845**, up from 798 (+47: `TopupTest` 26, `TopupLimitCheckerTest` 9, `TopupServiceTest` 6, `CheckoutPageLocalTest` 3, `PaymentsPropertiesTest` +2, `RazorpayGatewayAdapterTest` +1). `ModularityTests` green.
- **Integration tests:** core-app **547/547**, up from 505 (+42: `TopupControllerIT` 23, `TopupsMigrationIT` 6, `TopupRepositoryIT` 3, `TopupServiceIT` 3, `SecurityMatrixIT` +7 rows).
- **AML service:** 1/1.
- **JaCoCo (core-app):** 96.8% instructions (489 of 15,281 missed), 88.3% branches (124 of 1,064 missed). `payments`: 96.1% / 85.8%. `Topup`, `TopupService`, `TopupLimitChecker`, `TopupQueryService`, `TopupController`, `CheckoutPageConfig`: 100% instructions; `TopupWriter` 95% (the "row disappeared" guard).
- **Timing:** `TopupControllerIT` about 7 s, mostly the deliberate 3 s gateway timeout test. `TopupServiceIT` about 2 s.

Every new test passed on its first run.

---

### 6. What to learn from Day 12 Session B

1. **A remote call belongs between transactions, not inside one.** Save CREATED and commit, call the gateway, then save PENDING. If the call were inside the transaction, a 3 s timeout would hold the wallet lock for 3 s and block every transfer from that wallet. The price is a state you must handle explicitly: a top-up can be left CREATED if the process dies mid-way, and that's why part 3's reconciler exists.
2. **Make the rule executable.** `Propagation.NEVER` turns "don't call this inside a transaction" from a comment into an `IllegalTransactionStateException`, and a test proves it. The `NOWAIT` test checks the same thing from the database's side: while the gateway is "thinking", the wallet row is free.
3. **`@Transactional` works only through the proxy.** `TopupService` calling its own `@Transactional` method would silently run without a transaction (self-invocation). Putting the transactional steps in a separate bean (`TopupWriter`) is the simplest correct fix.
4. **Reserve the limit when the promise is made, not when the money arrives.** Counting open top-ups means the limit check at creation can't be gamed with parallel orders, and the wallet lock makes the count consistent. The concurrency test (10 × ₹2,000 against ₹10,000 → exactly 5) is the proof.
5. **A failed request must leave the system retryable.** The idempotency interceptor stores only 2xx responses, so after a 503 the key is released and the same request can be sent again. The FAILED row stays as an audit trail, and the retry creates a new top-up rather than reviving a row in an unknown state.
6. **Local-only tooling should be off by construction.** The checkout page lives outside `/static`, is wired only behind a flag that only `application-local.yml` sets, and has its own narrow security chain. A test proves that without the flag, the path is denied by the main chain.
