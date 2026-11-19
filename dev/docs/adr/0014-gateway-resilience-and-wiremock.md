# ADR 0014: Razorpay client resilience (programmatic Resilience4j) and WireMock for gateway tests

- Status: Accepted
- Date: 2026-11-19
- Spec: docs/SPEC.md §2 FR-P-4, FR-P-6; §6 (Configuration); §8 M7; §9 must-pass test 9

## Context

M7 adds the first outbound call on a money path: core-app creates Razorpay orders and reads payments. The SPEC
asks for a `PaymentGateway` port with a `RestClient` adapter, wrapped in a Resilience4j circuit breaker, a time
limit and retries for idempotent GETs, and for WireMock-based tests ("gateway timeout → 503
GATEWAY_UNAVAILABLE, circuit opens").

Resilience4j is in the CLAUDE.md stack, but not *how* to use it. WireMock is not in the stack, and CLAUDE.md
requires an ADR for every new library.

Constraints:
- Configuration lives in validated `@ConfigurationProperties` records (`trustledger.razorpay.*`); no hard-coded
  limits (CLAUDE.md, SPEC §6).
- Spring Boot 4.1 / Spring Framework 7.0.
- Order creation is **not idempotent** at Razorpay: the same `receipt` twice gives two orders.

## Decision 1: Resilience4j core modules, wired in code

Options:

1. **`resilience4j-spring-boot` starter + annotations** (`@CircuitBreaker`, `@TimeLimiter`, `@Retry`).
   Configuration then lives under `resilience4j.*`, outside our validated properties. The aspects only apply
   through Spring proxies. `@TimeLimiter` needs a `CompletionStage` return type. The Boot 4 starter line was
   new and less proven when this was written.
2. **Spring Framework 7's own `@Retryable` / `@ConcurrencyLimit`.** These exist, but there is no circuit breaker
   and no time limiter, so they cover only part of the need.
3. **Resilience4j core modules** (`resilience4j-circuitbreaker`, `-timelimiter`, `-retry`, version 2.4.0
   through `resilience4j-bom`), built in `RazorpayGatewayAdapter.create(RazorpayProperties)`.

**Chosen: option 3.** The whole pipeline is visible in one class, the settings come from
`trustledger.razorpay.*` with startup validation, and the adapter can be unit-tested without a Spring context.

The pipeline, outermost first: **Retry (GETs only) → CircuitBreaker → TimeLimiter → HTTP call**.

- Each retry attempt is one call the breaker sees. When the breaker opens mid-retry, the next attempt gets
  `CallNotPermittedException`, which is not retried, so callers fail fast.
- The breaker records only `GatewayUnavailableException` (timeout, 5xx, I/O, garbled body). A 4xx
  (`GatewayRejectedException`, new error code `GATEWAY_REJECTED`, 502) means Razorpay is up and our request is
  wrong. Counting it would let one bad request type cut every customer off from top-ups.
- The TimeLimiter runs the blocking call on a virtual thread and cancels it after `timeout` (3 s). The socket
  read timeout is the same value, so the abandoned request also ends on its own.
- `createOrder` (POST) is never retried. After a timeout we can't know whether Razorpay created the order, and a
  retry could create a second one. An unpaid orphan order simply expires; the customer retries the top-up with
  the same `Idempotency-Key` (M7 part 2).
- Spec values: count-based window 10, 50 % failure rate, at least 10 calls, 30 s open, 3 trial calls in
  half-open, 3 GET attempts with 200 ms ×2 backoff.

Breaker state metrics (`resilience4j-micrometer`) are left to M12.

## Decision 2: WireMock (standalone) for gateway tests

Options:

1. **`MockRestServiceServer`** (Spring). It replaces the request factory, so no real HTTP happens. That means
   no timeouts, no connection resets and no slow responses, which are exactly the cases this ADR is about.
2. **Hand-written stub controller / `HttpServer`.** Possible, but we would end up rebuilding delays, faults,
   scenarios and request verification ourselves.
3. **WireMock** `org.wiremock:wiremock-standalone` 3.13.2, test scope. A real HTTP server with fixed delays,
   faults (`CONNECTION_RESET_BY_PEER`), stateful scenarios (503, 503, 200) and request verification (count,
   Basic auth, exact JSON body). The standalone jar shades Jetty, so it cannot clash with Boot's own
   dependencies. 4.x is still beta; 3.13.2 is the newest stable release (2025-11-14).

**Chosen: option 3.** Unit tests (`RazorpayGatewayAdapterTest`) use a JUnit 5 `WireMockExtension`. Integration
tests share one in-process `WireMockServer` bean in `TestcontainersConfiguration`, and a
`DynamicPropertyRegistrar` points `trustledger.razorpay.base-url` at it, so the test context stays cached.

## Consequences

- `RazorpayProperties` refuses a live key (`key-id` must start with `rzp_test_`), blank secrets, and plain HTTP
  to anything but localhost. A misconfigured deployment fails at startup, not at the first payment.
- The JDK `HttpClient` is pinned to HTTP/1.1: on plain `http://` it otherwise tries an h2c upgrade, which
  WireMock's Jetty cancels for requests with a body.
- Found while testing: the JDK `HttpClient` itself re-sends an idempotent GET once when the connection is
  reset. One Resilience4j attempt can therefore be two TCP requests. This is harmless for GETs, and POST is never
  re-sent; the test asserts both.
- The breaker is one instance for all three operations. They share one upstream, so "Razorpay is down" is one
  fact.
