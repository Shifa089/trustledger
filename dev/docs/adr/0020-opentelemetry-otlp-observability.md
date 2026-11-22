# ADR 0020: OpenTelemetry over OTLP (`spring-boot-starter-opentelemetry`) for traces, metrics and logs

- Status: Accepted
- Date: 2026-11-22
- Spec: docs/SPEC.md §6 (Logging), §8 M12 ("Add Spring Boot's OpenTelemetry/OTLP support to both apps, exporting
  traces, metrics and logs to a grafana/otel-lgtm container in the compose "obs" profile. Make sure trace context
  propagates through Kafka headers."); CLAUDE.md Stack (changed by this ADR)

## Context

M12 wants one transfer to show up as **one trace across both services** (HTTP → outbox → Kafka → risk-aml-service),
log lines in Loki that carry the trace ID, and metrics in Grafana. Locally, one `grafana/otel-lgtm` container
(Grafana, Tempo, Loki, a Prometheus-compatible store and an OpenTelemetry collector) receives OTLP on 4317/4318.

None of this was on the classpath yet. Spring Boot 4 ships `spring-boot-starter-opentelemetry`: the OpenTelemetry
SDK, the Micrometer Tracing bridge (`micrometer-tracing-bridge-otel`), the OTLP span and log exporters, and
`micrometer-registry-otlp`. It does **not** connect Logback to the SDK's log pipeline. That needs the
OpenTelemetry instrumentation project's `opentelemetry-logback-appender-1.0`.

Four things had to be solved besides adding the dependencies:

1. **Hand-built Kafka templates.** `spring.kafka.template.observation-enabled` only reaches the template that Boot
   creates. core-app's dead-letter template and risk-aml-service's dead-letter and freeze-request templates are
   built in code and would send without a `traceparent`.
2. **The outbox's async hop.** Modulith sends `TransferCompletedV1` after the commit on Boot's task executor. Without
   context propagation, that thread has no current span and the Kafka record starts a new trace.
3. **ECS console logs.** A custom `logback-spring.xml` replaces Boot's console configuration, and Boot's includable
   `console-appender.xml` is pattern-only. Adding the appender in XML would have lost the ECS JSON format (SPEC §6).
4. **No collector running.** A plain `local` run without the `obs` compose profile must not log export errors every
   few seconds.

## Options

1. **Spring Boot's OpenTelemetry starter + the Logback appender, configured in properties and a little code.**
2. **The OpenTelemetry Java agent.** It instruments everything automatically, but it is a JVM agent outside Maven and
   Spring configuration. It brings its own instrumentation, which overlaps with Micrometer Observation, and its
   version has to be matched to Boot's by hand.
3. **Micrometer + Zipkin/Brave and a Prometheus scrape endpoint.** Two protocols and no log shipping. M12 names OTLP
   and LGTM.

## Decision

Option 1.

- **Dependencies:**
  - `spring-boot-starter-opentelemetry`: version managed by Boot.
  - `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.1-alpha`: the instrumentation release
    built against `opentelemetry-api` 1.62.0, the version Boot 4.1.2 manages. Newer appenders need a newer API than
    Boot provides. The version is in the parent POM's `dependencyManagement`.
  - `io.opentelemetry:opentelemetry-sdk-testing`, test scope, managed by Boot: in-memory span and log exporters for
    assertions.
- **Export is a Spring profile, like the compose profile.** The base `application.yml` keeps tracing on, so every log
  line has `traceId`/`spanId` and Kafka records carry `traceparent`. It turns the three OTLP exports off with
  `management.tracing.export.otlp.enabled`, `management.logging.export.otlp.enabled` and
  `management.otlp.metrics.export.enabled`. `application-obs.yml` turns them on, with the endpoints
  `${TRUSTLEDGER_OTLP_BASE_URL:http://localhost:4318}/v1/{traces,metrics,logs}` and a 15 s metric step. Run with
  `-Dspring-boot.run.profiles=local,obs`. `service.name` is `spring.application.name`.
- **Sampling 100% locally** (`local` and `obs` profiles, and tests). Other profiles keep Boot's 10% default until M14
  decides on AWS.
- **Kafka trace context:**
  - `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` are true in both apps.
  - The hand-built templates call `setObservationEnabled(true)` and `setObservationRegistry(...)` explicitly. A
    template that is not a bean never looks the registry up itself.
  - `spring.task.execution.propagate-context: true` (new in Boot 4) wraps the auto-configured executor in a
    context-propagating decorator, so the outbox's send continues the request's trace.
- **Logs:** `OpenTelemetryLogExportConfig` (in `shared`, one per app) attaches an `OpenTelemetryAppender` to the root
  logger in code and calls `OpenTelemetryAppender.install(openTelemetry)`. Boot's console setup is untouched (ECS
  stays). The appender copies only the `correlationId` from the MDC.
- **Console line format:** the `local` profile's correlation pattern becomes
  `[%X{correlationId:-}] [%X{traceId:-},%X{spanId:-}]`. ECS JSON includes the MDC on its own.

## Consequences

- CLAUDE.md Stack lists OpenTelemetry (ADR 0020).
- **Personal data:** Loki receives the same lines as the console. They are already masked (CLAUDE.md), and only
  `correlationId` comes from the MDC. Span attributes are Micrometer's defaults (HTTP method, route, status, Kafka
  topic), with no request bodies, headers or user data.
- **Known limit:** risk-aml-service's freeze-request and case-event relays send from an outbox table on a schedule.
  The row stores no trace context, so a freeze request starts a new trace instead of continuing the transfer's
  trace. Storing `traceparent` in the outbox row would close the gap. Not needed for M12's "one transfer, one trace",
  which ends at the AML consumer.
- **Tests:** `TestTelemetry` (in each app's `TestcontainersConfiguration`) registers recording `SpanExporter` and
  `LogRecordExporter` beans, which Boot adds to the SDK. It records only between `start()` and `stop()`, so other ITs
  don't fill memory. Covered by `TransferTracingIT`, `FreezeRequestTracingIT` and `ObservabilityConfigIT` in
  core-app, and by `KafkaTracingIT` and `ObservabilityConfigIT` in risk-aml-service.
- **Upgrades:** the alpha appender may change its API between releases. It is pinned, and the property
  comment explains how the version was chosen.

## Addendum (Day 23 Session B): metrics, actuator, dashboard

- **Custom metrics, no new dependency.** Micrometer meters registered by the code that changes the state:
  - `trustledger.transfers{status}` and `trustledger.transfer.amount` (rupee buckets 100 … 2,00,000) in
    `PaymentsMetrics` (core-app, payments). Counts are **state changes**: a held transfer counts as
    `HELD_FOR_REVIEW`, then `COMPLETED` or `REJECTED` on review; a refusal counts as `FAILED`.
  - `trustledger.topups{status}`: `PENDING`, `FAILED`, `CAPTURED`, `PARKED`, `EXPIRED`.
  - `trustledger.aml.alerts{rule,severity}` in `AmlAlertMetrics` (risk-aml-service); every configured rule is
    registered at 0.
  - `trustledger.reconciliation.mismatches{reason}` (M6, unchanged).
  - `trustledger.gateway.circuit.state{name,state}`: one gauge per Resilience4j state, 1 for the current one. Written
    by hand in `CircuitBreakerStateGauge` instead of adding `resilience4j-micrometer`: one class versus a new
    dependency with its own binder setup.
- **Counted after the commit** (`TransactionSynchronization.afterCommit`), so a rolled-back change is never counted.
  Tags are enum values only (no IDs, no personal data), so the number of series stays small.
- **Prometheus names.** OTLP metrics go through the LGTM collector into Prometheus, which turns dots into underscores,
  adds `_total` to counters and the unit to timers. `management.otlp.metrics.export.base-time-unit: seconds` gives
  `http_server_requests_seconds_*` (the default, milliseconds, gave `*_milliseconds_*`). No base unit on the amount
  summary, or Prometheus would append it to the name. `percentiles-histogram` is on for `http.server.requests`, so
  p95/p99 are computed in Prometheus from buckets (aggregatable across instances), not precomputed in the app.
- **Actuator.** Exposed: `health`, `info`, `metrics`. Public: `GET /actuator/health`, `/health/liveness` and
  `/health/readiness` (probes are on by default in Boot 4; readiness includes `db`). `show-details` and
  `show-components` are `when-authorized` with role ADMIN, so anonymous callers see only the status. Everything else
  under `/actuator` is ADMIN in both security chains, which still end in `denyAll()`.
- **Dashboard** `infra/grafana/trustledger-dashboard.json` (uid `trustledger-overview`), provisioned into the lgtm
  container by `infra/grafana/dashboards.yaml` (read-only mounts). `GrafanaDashboardTest` keeps its queries in step
  with the meter names.
