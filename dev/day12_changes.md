# Changes

## DAY12: Milestone M7 part 1: PaymentGateway port, Razorpay adapter, Resilience4j, WireMock tests

**Goal:** the gateway client that the top-up flow (M7 part 2) will call. The parts:
- **`PaymentGateway` port** (FR-P-4, FR-P-6): `createOrder(amount, receipt)`, `fetchPayment(id)`, `fetchOrderPayments(orderId)`.
- **`RazorpayGatewayAdapter`** with Spring `RestClient`: Basic auth from config, configurable base URL. Errors map to `GatewayUnavailableException` (503) or `GatewayRejectedException` (502).
- **Resilience4j:** TimeLimiter 3 s, CircuitBreaker (count window 10, 50 %, 30 s open), Retry for GETs only.
- **Validated `trustledger.razorpay.*` properties.** The test profile points at WireMock.

Branch: `feature/m7-topups-part1` (created from `master`), merged into `master`.

Decisions approved in the plan:
1. **A1: new error code `GATEWAY_REJECTED` → 502 Bad Gateway** for a Razorpay 4xx. The client can't fix it, and retrying won't help. That's different from the retryable 503 `GATEWAY_UNAVAILABLE`. One row was added to the SPEC §5 error table.
2. **`webhook-secret` is required now**, so the Razorpay properties are complete in one go. Local startup needs all three `RAZORPAY_*` variables (see section 3).
3. **Resilience4j core modules, configured in code** (no Boot starter or annotations), and **WireMock standalone** for tests. Both are recorded in **ADR 0014**.

---

### 1. How to verify Day 12

Run these from `dev/`. Start **Docker Desktop** first. One command per cell.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=RazorpayGatewayAdapterTest*,RazorpayPropertiesTest,PaymentGatewayTypesTest,ErrorCodeTest,ModularityTests"` | WireMock unit tests: wire format, 4xx/5xx mapping, retries, timeout, circuit open, half-open and closed again. Property validation. Module boundaries. No Docker needed. |
| 3 | `./mvnw -pl core-app verify -DskipUnitTests "-Dit.test=PaymentGatewayIT"` | The Spring context wires the adapter from the test profile and reaches the shared WireMock server with the fake test keys. |
| 4 | Add `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` to your `.env` (see `.env.example`) | Without them the `local` profile **refuses to start** (by design, section 3). |

**Results:** `./mvnw clean verify` is **BUILD SUCCESS**: 798 unit tests and 505 integration tests, all green. Details in section 5.

---

### 2. What changed and why

#### Payments module: domain (the port)

| File | What |
|---|---|
| `payments/domain/PaymentGateway.java` | The port: three methods. Includes the `order_…` / `pay_…` ID patterns and `requireOrderId` / `requirePaymentId`, so a malformed ID (`../orders`) is refused **before** any HTTP request. |
| `GatewayOrder`, `GatewayPayment` (records) | Amounts are `Money` (long paise). IDs are checked in the constructors. |
| `GatewayPaymentStatus` | `created/authorized/captured/refunded/failed`; anything else → `UNKNOWN`. It never throws, because Razorpay adding a status must not break reconciliation. `UNKNOWN` never credits a wallet. |
| `GatewayUnavailableException` | `TrustLedgerException(GATEWAY_UNAVAILABLE)` → 503. Fixed client detail; the cause is kept only for logs. |
| `GatewayRejectedException` | `TrustLedgerException(GATEWAY_REJECTED)` → 502. Carries the HTTP status and Razorpay's `error.code` (e.g. `BAD_REQUEST_ERROR`) for logs and the future reconciler. Razorpay's `description` never reaches the client. |

#### Payments module: infrastructure (the adapter)

| File | What |
|---|---|
| `RazorpayGatewayAdapter.java` | `RestClient` on the JDK `HttpClient` (HTTP/1.1, no redirects, connect timeout, read timeout = `timeout`). `POST /v1/orders` sends `{amount: <paise>, currency: "INR", receipt}`, then `GET /v1/payments/{id}` and `GET /v1/orders/{id}/payments`. The pipeline is **Retry (GETs only) → CircuitBreaker → TimeLimiter → HTTP**. Package-private; vendor exceptions never leave it. |
| `RazorpayClientConfig.java` | One bean, closed on shutdown (the virtual-thread executor). Nothing connects at startup. |
| `RazorpayProperties.java` | `trustledger.razorpay.*`, validated at startup. Refuses: a key-id that isn't `rzp_test_…` (live keys never start, and the message doesn't echo the key), blank secrets, http to a non-localhost host, timeouts outside 100 ms–10 s, breaker and retry values out of range (e.g. minimum calls > window). `toString()` masks both secrets. |

**Error mapping:**

| What happens | Exception | API result | Counted by the breaker | Retried (GETs) |
|---|---|---|---|---|
| 2xx with a valid body | (none) | | success | no |
| 4xx | `GatewayRejectedException` | 502 `GATEWAY_REJECTED` | **no** (ignored) | **no** |
| 5xx | `GatewayUnavailableException` | 503 `GATEWAY_UNAVAILABLE` | yes | yes |
| Longer than `timeout` (3 s) | `GatewayUnavailableException` (cause `TimeoutException`) | 503 | yes | yes |
| Connection reset / refused | `GatewayUnavailableException` (cause `ResourceAccessException`) | 503 | yes | yes |
| 200 with garbled JSON or a missing amount | `GatewayUnavailableException` | 503 | yes | yes |
| Circuit open | `GatewayUnavailableException` (cause `CallNotPermittedException`) | 503 | n/a | **no** (fail fast) |

#### Shared, config, docs

| File | What |
|---|---|
| `shared/ErrorCode.java` | `GATEWAY_REJECTED(BAD_GATEWAY)`. |
| `application.yml` | The `trustledger.razorpay` block with the SPEC values; keys from `RAZORPAY_*` env vars. |
| `.env.example` | The three `RAZORPAY_*` variables, with a note on where to find test keys. |
| `pom.xml`, `core-app/pom.xml` | `resilience4j-bom` 2.4.0 (circuitbreaker, timelimiter, retry) and `wiremock-standalone` 3.13.2 (test). |
| `docs/adr/0014-gateway-resilience-and-wiremock.md` | Options and choices for both libraries, and why POST is never retried. |
| `docs/SPEC.md` §5 | `GATEWAY_REJECTED` → 502 row. |
| `CLAUDE.md` | Stack line: WireMock (ADR 0014). |
| `payments/package-info.java` | Mentions the gateway port. |

#### Tests

| Test | Kind | Covers |
|---|---|---|
| `RazorpayGatewayAdapterTest` (25) | Unit, WireMock, no Spring | **Wire format:** path, Basic auth, exact JSON body with integer paise; payment, failed payment with error code, unknown status, order payments list and empty list. **Input checks:** bad IDs, zero amount, long or blank receipt, with zero requests sent. **Rejections:** 400 with code, sent once; 401 without leaking the secret; 404 with an HTML body; 12 × 400 leaves the circuit CLOSED. **Retries/timeouts:** 503, 500, 200 → success after 3 requests; always 503 → 3 then give up; POST 500 → 1 request; 3 s delay → fails at about 300 ms; connection reset (GET retried, POST sent once); garbled body; missing amount. **Circuit:** 10 failures → OPEN, the 11th call fails fast with **no request**; the breaker opens mid-retry and stops it; half-open trial calls close it again; timeouts count as failures. |
| `RazorpayPropertiesTest` (13) | Unit | SPEC values valid; live key / bare prefix / blank refused; secrets required; https except localhost; bounds on every timeout, breaker and retry value; `toString` hides secrets; kebab-case binding; a live key fails context startup. |
| `PaymentGatewayTypesTest` (23) | Unit | Status mapping, ID patterns (path traversal, query, wrong prefix, too long), record checks, exception codes and statuses. |
| `ErrorCodeTest` | Unit | +1 row: `GATEWAY_REJECTED, 502`. |
| `PaymentGatewayIT` (2) | Integration | Context wiring from the test profile → shared WireMock: order round trip with the fake keys; 4xx → `GatewayRejectedException`. |
| `TestcontainersConfiguration` | Test infra | One in-process `WireMockServer` bean + a `DynamicPropertyRegistrar` for `trustledger.razorpay.base-url`. Same config for every IT, so the cached context (and the containers) are still shared. |

---

### 3. Machine setup for Day 12 (not in Git)

- **`.env` needs `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`.** Get the keys from the Razorpay Dashboard in **Test Mode** → Account & Settings → API Keys. The key ID must start with `rzp_test_`, or core-app refuses to start. The webhook secret is whatever you type when you create the webhook (next session); any non-blank value works until then.

### 4. Deferred

- **`POST /topups`, the `Topup` aggregate, `payments.topups` / `payments.webhook_events`, the webhook controller, the `PendingTopupReconciler`, `checkout.html`, ADR 0006** (over-limit top-ups): M7 parts 2 and 3. The "circuit open → HTTP 503 ProblemDetail" test goes through `POST /topups` once it exists.
- **Breaker state metrics and a health indicator** (`resilience4j-micrometer`): M12. `circuitState()` is already there to hook into.
- **The wallet lock-mode trap from Day 11:** still needed before the top-up credit (M7 part 2).
- **The checkout key ID:** `POST /topups` must return the key ID. The next session should expose it through the port rather than by injecting `RazorpayProperties` outside infrastructure.

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS**.

- **Unit tests:** core-app **798/798**, up from 736 (+62: `RazorpayGatewayAdapterTest` 25, `PaymentGatewayTypesTest` 23, `RazorpayPropertiesTest` 13, `ErrorCodeTest` +1). `ModularityTests` green.
- **Integration tests:** core-app **505/505**, up from 503 (+2, `PaymentGatewayIT`).
- **AML service:** 1/1.
- **JaCoCo (core-app):** 96.6% instructions (488 of 14,425 missed), 88.0% branches (125 of 1,038 missed). `payments`: 95.4% / 84.6%. `RazorpayGatewayAdapter`: 94.6% instructions (527/557), 17/24 branches. Domain gateway types: 100%.
- **Timing:** `RazorpayGatewayAdapterTest` about 8 s (mostly the deliberate delays: the 10 × 300 ms timeout test and the 500 ms open-state wait). `PaymentGatewayIT` under 1 s (it shares the cached context).

**Fixes found while testing:**
1. **POST to WireMock failed with `RST_STREAM: Stream cancelled`.** On plain `http://` the JDK `HttpClient` tried an h2c (HTTP/2) upgrade, and WireMock's Jetty cancelled it for a request with a body. GETs worked, so only `createOrder` failed. Fix: the client is pinned to HTTP/1.1 (commented in code, recorded in ADR 0014).
2. **Connection-reset GET made 6 requests, not 3.** The JDK `HttpClient` re-sends an idempotent GET once on a reset connection, before Resilience4j sees anything. That's harmless for GETs. The test now documents it (3 to 6), and a new test proves a reset POST is sent exactly once.
3. **The 401 test failed in the full build but passed on its own.** It ran first in the JVM, and that cold first request (class loading, Jackson init) took longer than the test's 300 ms limit. The adapter was right: it reported a timeout. Fix: normal tests use a 2 s limit; only the two timeout tests use 300 ms.

---

### 6. What to learn from Day 12

1. **Decorator order is the design.** Retry outside the breaker means every attempt is a recorded call, and an open circuit stops the retry loop at once. Put Retry inside instead, and a single "call" would hide three failures from the breaker. The order you wrap things in decides what each layer sees.
2. **Only count failures that say something about the dependency.** A 4xx means *our* request is wrong. If the breaker counted it, one buggy request type could cut every customer off from top-ups. Tell the breaker what to record and what to ignore explicitly, and test both.
3. **Retry only what is safe to repeat.** A GET is idempotent; Razorpay's "create order" is not. After a timeout you don't know whether the order exists, so a retry could create two. The safe recovery is one level up: the customer retries the top-up with the same `Idempotency-Key`.
4. **Your HTTP client has opinions you didn't configure.** Two surprises today: (a) on plain `http://` the JDK `HttpClient` tries an HTTP/2 upgrade, which WireMock's Jetty cancels for a POST with a body (`RST_STREAM`), so it's pinned to HTTP/1.1. (b) It quietly **re-sends an idempotent GET once** on a reset connection, so one Resilience4j attempt can be two requests. A test that counts requests found both; a mocked client never would.
5. **A test double must be able to fail like the real thing.** `MockRestServiceServer` can't be slow, can't reset a connection, and can't half-recover. WireMock can (`withFixedDelay`, `Fault.CONNECTION_RESET_BY_PEER`, scenarios), and those are exactly the paths that matter in production.
6. **Validate config like input.** A live key in a test environment is a real incident. Refusing anything but `rzp_test_` at startup turns that mistake into a failed deploy, and the error message doesn't echo the key back into the logs.
