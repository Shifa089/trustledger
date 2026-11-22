# Changes

## DAY23 Session B (M12): custom metrics, actuator security, provisioned Grafana dashboard

**Goal** (SPEC §6 "Security", §8 M12):
1. Metrics: `trustledger_transfers_total{status}`, `trustledger_transfer_amount`, `trustledger_topups_total{status}`,
   `trustledger_aml_alerts_total{rule}`, `trustledger_reconciliation_mismatches`, and the gateway circuit-breaker
   state.
2. Actuator: health public with liveness and readiness; everything else ADMIN only.
3. A provisioned `infra/grafana/trustledger-dashboard.json`: request rate, error rate, p95/p99 per endpoint,
   transfers by status, AML alerts by rule, mismatches, circuit state. README "Observability" section.

With Session A (traces, logs, OTLP export) this completes M12.

Branch: `feature/day23b-metrics-dashboard` (from `master`, which already has Day 22 and Day 23 Session A).

Decisions (approved in the plan):
- **No new dependency.** The circuit-breaker state is a hand-written gauge (`CircuitBreakerStateGauge`), not
  `resilience4j-micrometer`. ADR 0020 gets an addendum instead of a new ADR.
- **`trustledger.transfers{status}` counts state changes.** A held transfer counts once as `HELD_FOR_REVIEW` and
  once more as `COMPLETED` or `REJECTED` when an officer decides. So held transfers show up when they happen, not
  only after review.
- **Count after the commit**, so a rolled-back change is never counted.

---

### 1. Metrics

Names are Micrometer's. After the arrow is the name Prometheus gives them when they arrive over OTLP (checked
against the real LGTM container, see §5).

| Meter | Prometheus | Where |
| --- | --- | --- |
| `trustledger.transfers{status}` | `trustledger_transfers_total` | `TransferService` (COMPLETED, HELD_FOR_REVIEW; FAILED for a refusal), `HeldTransferService` (approve: COMPLETED, reject: REJECTED) |
| `trustledger.transfer.amount` | `trustledger_transfer_amount_bucket/_sum/_count` | completed or held transfers, in rupees; buckets ₹100, 1k, 5k, 10k, 45k, 50k, 1L, 2L |
| `trustledger.topups{status}` | `trustledger_topups_total` | `TopupService` (PENDING, FAILED), `TopupSettlementService` (CAPTURED, PARKED, FAILED, EXPIRED) |
| `trustledger.aml.alerts{rule,severity}` | `trustledger_aml_alerts_total` | `RuleEngine.raise` in risk-aml-service |
| `trustledger.reconciliation.mismatches{reason}` | `trustledger_reconciliation_mismatches_total` | unchanged (M6). Prometheus adds `_total` to every counter |
| `trustledger.gateway.circuit.state{name,state}` | `trustledger_gateway_circuit_state` | one gauge per Resilience4j state: 1 for the current one, 0 for the others |

**New classes:**
- `payments/application/PaymentsMetrics` (core-app). Registers every status at 0, so a dashboard shows a 0 instead of
  "no data". Inside a transaction it counts in `TransactionSynchronization.afterCommit`; outside one (the top-up
  service runs with `Propagation.NEVER`) it counts at once.
  - `transferAccepted(status, amount)`: COMPLETED or HELD_FOR_REVIEW, and the amount, after the commit.
  - `transferReviewed(status)`: approve or reject, after the commit.
  - `transferRefused()`: counted at once. The rollback is the expected result of a refusal, not a reason to skip it.
  - `topup(status)`.
- `TransferService.transfer` now wraps steps 2–7 (moved into a private `execute`) in a `try`/`catch
  (TrustLedgerException)` that counts the refusal and rethrows. An idempotent replay never reaches the service, so it
  isn't counted twice.
- `payments/infrastructure/CircuitBreakerStateGauge` (core-app), registered by `RazorpayClientConfig` through the new
  `RazorpayGatewayAdapter.registerMetrics(MeterRegistry)`.
- `rules/application/AmlAlertMetrics` (risk-aml-service). Registers every configured rule at 0 and counts after the
  commit, so a rolled-back evaluation that Kafka retries is counted once.

**Bug found by the first full `verify`:** the first version deferred the count whenever
`TransactionSynchronizationManager.isSynchronizationActive()` was true. But Spring turns synchronization on even for
the "empty" transaction of a `Propagation.NEVER` method (`TopupService.create`). When that method throws, there is no
commit, so `afterCommit` never ran and a FAILED top-up was never counted (`TopupMetricsIT` caught it). Both metric
classes now check `isActualTransactionActive()`, and `PaymentsMetricsTest` has a regression test for it.

Tags are enum values and rule codes only: no wallet or user IDs, no personal data, and few series.

### 2. Configuration (`application.yml`, both apps)

- `management.otlp.metrics.export.base-time-unit: seconds`. Timers arrive as `http_server_requests_seconds_*` (the
  Prometheus convention). With the default, milliseconds, they arrived as `*_milliseconds_*`, which Session A's
  manual check showed.
- `management.metrics.distribution.percentiles-histogram.http.server.requests: true`. Histogram buckets, so Grafana
  computes p95 and p99 per endpoint with `histogram_quantile`.
- Actuator:
  - `management.endpoints.web.exposure.include: health,info,metrics`.
  - `management.endpoint.health.show-details` and `show-components`: `when-authorized`, with `roles: ADMIN`.
  - `management.endpoint.health.group.readiness.include: readinessState,db`.
  - Liveness and readiness probes are on by default in Boot 4.1 (checked in the property metadata), so no
    `probes.enabled` was needed.

### 3. Actuator security (`SecurityConfig`, both apps)

The public rule is now exactly `GET /actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness`
(it was `/actuator/health/**`). So `/actuator/health/db` and other component paths need ADMIN, like the rest of
`/actuator/**`. The chains still end in `denyAll()`.

| Call | Anonymous | CUSTOMER / officer / auditor | ADMIN |
| --- | --- | --- | --- |
| `/actuator/health` | 200, status only | 200, status only | 200, with components |
| `/actuator/health/liveness`, `/readiness` | 200 | 200 | 200 (readiness shows `db`) |
| `/actuator/health/db` | 401 | 403 | 200 |
| `/actuator/metrics`, `/actuator/info` | 401 | 403 | 200 |
| `/actuator/env` | 401 | 403 | 404 (not exposed) |

### 4. Grafana dashboard

- `infra/grafana/trustledger-dashboard.json`: uid `trustledger-overview`, datasource uid `prometheus` (the one LGTM
  provisions), and a `$service` variable from `job` (`core-app`, `risk-aml-service`). Three rows:
  - HTTP: request rate, error rate (5xx and 4xx), p95 and p99 per `job`+`method`+`uri`. `/actuator` calls are
    excluded.
  - Money movement: transfers by status, transfer amount distribution, top-ups by status, payment gateway circuit
    (state timeline: 0 CLOSED, 1 HALF_OPEN, 2 OPEN).
  - Risk and integrity: AML alerts by rule, reconciliation mismatches (red above 0).
- `infra/grafana/dashboards.yaml`: a dashboard provider (folder "TrustLedger", reloads every 30 s, no UI edits).
- `infra/docker-compose.yml`: the `lgtm` service mounts both files read-only, into
  `/otel-lgtm/grafana/conf/provisioning/dashboards/` and `/otel-lgtm/trustledger-dashboards/`.

### 5. Manual check against the real LGTM container

1. I recreated `lgtm` with the mounts. Grafana's search API listed the folder *TrustLedger* and the dashboard
   *TrustLedger overview*.
2. I ran both apps with `local,obs` and sent refused transfers (customer1's wallet is frozen, so 409 `WALLET_FROZEN`)
   and wallet reads.
3. Prometheus had every expected name: `http_server_requests_seconds_{bucket,count,sum}`,
   `trustledger_transfers_total`, `trustledger_transfer_amount_bucket`, `trustledger_topups_total`,
   `trustledger_aml_alerts_total`, `trustledger_reconciliation_mismatches_total`,
   `trustledger_gateway_circuit_state`. `trustledger_transfers_total{status="FAILED"}` was 5, the other statuses 0,
   and R1–R4 at 0 from risk-aml-service.
4. I ran every panel query against Prometheus. All 11 succeeded; all returned data except the 5xx error rate (no
   server errors had happened).

**Bug found by the check:** the circuit panel first used
`2 * state{state="OPEN"} + state{state="HALF_OPEN"}`. In PromQL, `+` only matches series with identical labels, and
the two sides differ in `state`, so the result was empty. The query now aggregates each side `by (name)` first, and
`GrafanaDashboardTest` checks the shape.

Not checked by hand: the admin calls (admin1 needs a TOTP code for the password grant). The ITs cover them.

### 6. Tests

**Unit:**
- `PaymentsMetricsTest`:
  - every status registered at 0;
  - counts at once outside a transaction;
  - inside one, only after `afterCommit`; nothing after a rollback;
  - a refusal counts at once;
  - the rupee bucket bounds (₹46,000 lands in the 50k bucket).
- `CircuitBreakerStateGaugeTest`: one series per state, and exactly the current state is 1 through
  CLOSED → OPEN → HALF_OPEN → CLOSED; `name` tag.
- `RuleEngineTest`: a new test counts alerts per rule. A suppressed repeat isn't counted, and a rule that never fired
  is registered at 0. Its `engine(...)` helper now passes an `AmlAlertMetrics`.
- `GrafanaDashboardTest` (core-app, reads `../infra/grafana/trustledger-dashboard.json`):
  - the JSON parses, its uid is stable and panel IDs are unique;
  - every panel uses datasource `prometheus`;
  - the queries contain every required metric under its Prometheus name, built from the meter constants;
  - p95/p99 are per `le, job, method, uri`, and the error rate uses `outcome="SERVER_ERROR"`;
  - the business panels group by `status`, `rule` and `reason`;
  - the circuit query aggregates by name.
- Constructor updates in `TransferEventsTest`, `HeldTransferServiceTest`, `TopupServiceTest` and
  `TopupSettlementServiceTest` (they pass a `PaymentsMetrics` over a `SimpleMeterRegistry`).

**Integration (Testcontainers).** The registry is shared by the whole test context, so every test compares before
and after.
- `TransferMetricsIT`:
  - completed → COMPLETED +1 and the amount recorded;
  - refused (over balance) → FAILED +1 and no amount;
  - held → HELD_FOR_REVIEW, then rejected → REJECTED;
  - held, then approved → COMPLETED, with the amount recorded once;
  - the same `Idempotency-Key` twice over HTTP → counted once.
- `TopupMetricsIT` (WireMock as Razorpay):
  - order created → PENDING, then the capture → CAPTURED;
  - 503 → FAILED;
  - 10 failures open the circuit, and the gauge moves from CLOSED=1 to OPEN=1.
- `ActuatorEndpointsIT` (core-app):
  - the probes are public and UP;
  - anonymous and CUSTOMER health calls have no `components`, while ADMIN sees `db` and `readinessState`;
  - readiness includes `db`;
  - `/actuator/metrics` lists the custom meters.
- `SecurityMatrixIT`: 13 new rows (probes, `health/db`, `metrics`, `info`, `env`, per role).
- `AmlSecurityIT` (risk-aml-service): probes public; health components only for ADMIN; `metrics` 401/403/200; the
  alert meter has R1–R4 as `rule` tags; `env` not exposed (404).
- `AmlAlertMetricsIT` (risk-aml-service): a ₹1,00,000 `TransferCompletedV1` on Kafka. R3 alerts are saved, and
  `trustledger.aml.alerts{rule=R3}` rises by at least that number (`>=`, because another class's late Kafka record
  could add R3s too).

### 7. Docs

- ADR 0020: an addendum for Session B (metric names and Prometheus naming, after-commit counting, the hand-written
  gauge, actuator, dashboard provisioning).
- README "Observability": how to open the dashboard, a panel → metric table, and the actuator rules.

### 8. Verification status

`./mvnw verify` **passed** on this branch, the whole reactor (the second run; the first found the
NEVER-propagation bug above):

| Module | Unit tests | Integration tests |
| --- | --- | --- |
| core-app | 1306 | 798 |
| risk-aml-service | 107 | 58 |
| event-contracts | 36 | |

`ModularityTests` and `ArchitectureRulesTest` are green.

### What to learn from it

- **Count business events after the commit.** A counter incremented inside a transaction that then rolls back
  reports something that never happened. `TransactionSynchronization.afterCommit` ties the metric to the data. The
  exception is a refusal, whose rollback *is* the outcome.
  Check `isActualTransactionActive()`, not `isSynchronizationActive()`: Spring also turns synchronization on for
  NEVER/SUPPORTS scopes with no transaction, and an `afterCommit` there never runs when the method throws.
- **Know what Prometheus does to your names.** Over OTLP, `trustledger.transfers` becomes
  `trustledger_transfers_total`, and a timer gains its unit (`_seconds` or `_milliseconds`). A base unit on a summary
  would be appended too. Look at the real names before writing queries, and test the dashboard against the meter
  constants so a rename can't silently empty a panel.
- **Histograms, not precomputed percentiles.** `percentiles-histogram` exports buckets, and Prometheus computes
  p95/p99 with `histogram_quantile` after summing across instances. Percentiles computed in the app can't be
  averaged across instances.
- **PromQL arithmetic matches labels exactly.** `a{state="OPEN"} + a{state="HALF_OPEN"}` is empty because the
  `state` labels differ. Aggregate each side (`max by (name)`) or use `ignoring(...)`. Only a run against real data
  showed this.
- **Keep labels low-cardinality.** Enum statuses and rule codes give a handful of series. A wallet ID as a label would
  create one series per wallet, and would put identifiers into the metrics store.
- **Actuator: expose little, and show details by role.** Probes must be public for orchestrators, and they reveal
  nothing beyond UP/DOWN. Component details, metrics and info are internal information and need ADMIN. Endpoints
  nobody needs (`env`) are simply not exposed.
