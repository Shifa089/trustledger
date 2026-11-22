# Changes

## DAY23 Session A (M12): OpenTelemetry/OTLP for both apps, trace context through Kafka

**Goal** (SPEC §6 "Logging", §8 M12):
1. Compose profile `obs`: one `grafana/otel-lgtm` container (Grafana 3000, OTLP gRPC 4317, OTLP HTTP 4318).
2. Both apps export traces, metrics and logs through Spring Boot's OpenTelemetry/OTLP support, sampling 100%
   locally.
3. Trace context goes through Kafka (observation on KafkaTemplate and the listener containers), and log lines carry
   `traceId` and `spanId`.

Session B (rest of M12) still has: the custom metrics (`trustledger_transfers_total{status}` and so on), the
provisioned Grafana dashboard JSON, and actuator security.

Branch: `feature/day23a-observability` (created from `feature/day22-transaction-history`, which was not merged yet,
so merging this branch also brings Day 22 into `master`).

Decisions (approved in the plan):
- **New dependencies, with ADR 0020 and a line in the CLAUDE.md stack:**
  - `spring-boot-starter-opentelemetry` (Boot-managed);
  - `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.1-alpha`;
  - `io.opentelemetry:opentelemetry-sdk-testing` (test scope).
- **Export is turned on by a Spring profile `obs`, like the compose profile.** Without it, tracing still runs (trace
  and span IDs in every log line, `traceparent` on every Kafka record), but nothing is sent. A plain `local` run with
  no LGTM container logs no export errors.

---

### 1. Compose

`infra/docker-compose.yml`: the `lgtm` service (`grafana/otel-lgtm:0.40.0`, ports 3000/4317/4318, healthcheck,
profile `obs`) was already there from Day 1 as a placeholder. Its comment now says what it is and how the apps send
to it (Spring profile `obs`). No other change.

```bash
docker compose -f infra/docker-compose.yml --profile obs up -d
```

### 2. Dependencies (`pom.xml`, `core-app/pom.xml`, `risk-aml-service/pom.xml`)

- **`spring-boot-starter-opentelemetry`:** OpenTelemetry SDK, the Micrometer Tracing bridge, the OTLP span and log
  exporters, and `micrometer-registry-otlp`.
- **`opentelemetry-logback-appender-1.0`:** Boot's starter has a log pipeline but doesn't connect Logback to it. The
  version (`otel-logback-appender.version`, in the parent POM's `dependencyManagement`) is **2.28.1-alpha**, the
  instrumentation release built against `opentelemetry-api` **1.62.0**, which is what Boot 4.1.2 manages. 2.34 (the
  newest) is compiled against 1.68 and would pull a mixed OpenTelemetry API onto the classpath.
- **`opentelemetry-sdk-testing`** (test): `InMemorySpanExporter` / `InMemoryLogRecordExporter`.

### 3. Configuration

**`application.yml`** (both apps):
- `spring.task.execution.propagate-context: true` (new in Boot 4.x): the auto-configured executor copies the trace
  context (and the MDC) into `@Async` work. That includes the outbox's Kafka send after the commit.
- `spring.kafka.template.observation-enabled: true` and `spring.kafka.listener.observation-enabled: true`: the
  producer writes a W3C `traceparent` header, and the listener continues that trace in a consumer span.
- `management.tracing.export.otlp.enabled`, `management.logging.export.otlp.enabled`,
  `management.otlp.metrics.export.enabled`: all `false` by default. Metrics in particular would otherwise try to send
  to `localhost:4318` every minute in every profile.

**`application-obs.yml`** (new, both apps):
- the three export flags set to `true`;
- endpoints `${TRUSTLEDGER_OTLP_BASE_URL:http://localhost:4318}/v1/traces`, `/v1/metrics` and `/v1/logs`;
- metric step 15 s;
- sampling 1.0;
- resource attribute `deployment.environment.name=local`.

`service.name` comes from `spring.application.name` (`core-app`, `risk-aml-service`). Run with:

```bash
./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local,obs
./mvnw -pl risk-aml-service spring-boot:run -Dspring-boot.run.profiles=local,obs
```

**`application-local.yml`** (both apps):
- sampling 1.0 (Boot's default is 10%);
- the correlation part of each console line is now `[%X{correlationId:-}] [%X{traceId:-},%X{spanId:-}] `. Before,
  core-app's `[%X{correlationId:-}]` override hid the trace IDs. Outside `local`, ECS JSON includes `traceId` and
  `spanId` from the MDC on its own.

**Test `config/application.yml`** (both apps): sampling 1.0, and a 100 ms export delay for the span and log
processors, so tests see finished spans quickly. OTLP stays off.

### 4. Logs to OpenTelemetry: `OpenTelemetryLogExportConfig` (both apps, `shared.observability`)

- An `InitializingBean` attaches an `OpenTelemetryAppender` (named `OTEL`) to the root logger, once per Logback
  context, and calls `OpenTelemetryAppender.install(openTelemetry)` with Boot's SDK.
- **Why in code and not in `logback-spring.xml`:** a custom Logback file replaces Boot's console setup, and Boot's
  includable `console-appender.xml` is pattern-only. The ECS JSON format (SPEC §6) would have been lost.
- **What goes to Loki:** the same lines as the console, which are already masked. Of the MDC, only `correlationId`
  is copied (`setCaptureMdcAttributes`). Each record gets the trace and span ID of the span that was current when it
  was logged.

### 5. Kafka: the hand-built templates

`spring.kafka.template.observation-enabled` only reaches the template that Boot creates. A template built in code,
and especially one that isn't a bean, never looks up the `ObservationRegistry` itself. Both are now set explicitly:

| Template | Where | Effect |
| --- | --- | --- |
| core-app dead-letter template (not a bean, on purpose) | `shared.events.KafkaConsumerConfig.deadLetterTemplate(factory, registry)` | a DLT record continues the failed record's trace |
| risk-aml-service `deadLetterTemplate` | `shared.kafka.KafkaConsumerConfig` (`observed(...)`) | same |
| risk-aml-service `freezeRequestTemplate` (freeze requests and case events) | same | the relay's sends carry `traceparent` |

The outbox's `TransferCompletedV1` goes through Boot's own template (Modulith's Kafka externalization), so the
property is enough there.

**Known limit (in ADR 0020):** risk-aml-service's relays read their outbox rows on a schedule, and the rows store no
trace context. A freeze request therefore starts a new trace. M12's "one transfer, one trace" ends at the AML
consumer, so this doesn't affect it.

### 6. Tests

**Test support:**
- `TestTelemetry` (core-app and risk-aml-service): recording `SpanExporter` and `LogRecordExporter` beans, which Boot
  adds to its SDK. It records only between `start()` and `stop()`, so the other ITs don't fill memory.
- `TestTelemetry` is registered in each app's `TestcontainersConfiguration`, so every IT still shares one context and
  one set of containers.
- `KafkaTestClient` (risk-aml-service): a `sendContract(..., headers)` overload.

| Test | What it proves |
| --- | --- |
| core-app `TransferTracingIT` | a transfer run inside a parent observation: the `TransferCompletedV1` record (sent by the outbox, on another thread, after the commit) has `traceparent` `00-<same traceId>-…`, and a PRODUCER span for the topic is in the same trace |
| core-app `FreezeRequestTracingIT` | an `AccountFreezeRequestedV1` with a known `traceparent` freezes the wallet in a CONSUMER span of that trace, a child of the sender's span; a record that ends in the DLT keeps the trace ID |
| core-app `ObservabilityConfigIT` | a log line inside a span reaches OpenTelemetry with that trace and span ID (and the `correlationId` attribute), and the ECS console line has `"traceId"` / `"spanId"`; the `OTEL` appender is on the root logger exactly once (attaching again is a no-op); sampling is 1.0; no OTLP span exporter, log exporter or meter registry without `obs` |
| risk-aml-service `KafkaTracingIT` | a `TransferCompletedV1` with core-app's `traceparent` is consumed in a CONSUMER span of that trace, a child of core-app's producer span; `freezeRequestTemplate` writes `traceparent` of the current trace and a PRODUCER span |
| risk-aml-service `ObservabilityConfigIT` | as core-app's (the console line is the pattern format, with trace and span ID) |

One fix while writing the tests: the `traceparent` that Boot writes ends in flags `03`, not `01`. W3C Trace Context
level 2 adds a "random trace ID" bit next to "sampled". The assertions accept any flags byte.

### 7. Manual check against the real LGTM container

`docker compose --profile obs up -d lgtm`, then risk-aml-service with `local,obs`, then one `TransferCompletedV1` with
a known `traceparent`, sent with `kafka-console-producer` to the compose broker:
- **Tempo** (through Grafana's datasource proxy): the trace has the span `trustledger.payments.transfer-completed.v1
  process` from `service.name=risk-aml-service`.
- **Loki:** `{service_name="risk-aml-service"}` has the "Recorded transfer…" line with the same `trace_id`, its
  `span_id`, and `correlationId`.
- **Prometheus:** JVM, process and Kafka client metrics from `risk-aml-service`.

core-app's HTTP → Kafka → risk-aml-service trace in Grafana is the demo check for the end of M12 (Session B, along
with the dashboard).

### 8. Docs

- `docs/adr/0020-opentelemetry-otlp-observability.md` (new).
- `CLAUDE.md`: stack line `OpenTelemetry over OTLP via spring-boot-starter-opentelemetry +
  opentelemetry-logback-appender (ADR 0020)`.
- `README.md`: an "Observability" section (how to start, what to open in Grafana), Grafana in the URL table, ADR
  0020, and the tech stack line.

### 9. Verification status

`./mvnw verify` **passed** on this branch, the whole reactor:

| Module | Unit tests | Integration tests |
| --- | --- | --- |
| core-app | 1290 | 773 |
| risk-aml-service | 106 | 54 |
| event-contracts | 36 | |

`ModularityTests` and `ArchitectureRulesTest` are green.

### What to learn from it

- **Tracing is three separate problems:** creating spans (Micrometer Observation), carrying the context across
  boundaries (HTTP headers, Kafka headers, threads), and exporting (OTLP). Most "my trace is broken" bugs are the
  second one: a hand-built `KafkaTemplate`, or an `@Async` hop without context propagation.
- **Boot properties only configure the beans Boot creates.** `spring.kafka.template.observation-enabled` did nothing
  for the three templates built in code. The same is true for executors, `RestClient`s and so on. When you build
  something yourself, you take over its configuration.
- **W3C `traceparent` = `version-traceId-parentSpanId-flags`.** Over Kafka, the consumer span's parent is the
  producer's span. That one header is what makes two services one trace in Tempo.
- **Trace ID in logs is the link between Loki and Tempo.** Put it in the MDC (Micrometer does this), print it on the
  console, and send it as the log record's span context (the OTel appender does this), so Grafana can jump from a
  log line to its trace.
- **Match versions to the platform BOM.** An alpha instrumentation artifact must be built against the same
  `opentelemetry-api` that Boot manages. Check its POM rather than taking the newest version.
- **Make export opt-in locally.** Telemetry that fails to send is noise. A profile keeps the signal (IDs in logs) on
  all the time and the shipping on only when a collector is there.
