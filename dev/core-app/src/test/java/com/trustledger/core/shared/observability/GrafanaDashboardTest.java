package com.trustledger.core.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.payments.application.PaymentsMetrics;
import com.trustledger.core.wallet.application.ReconciliationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M12: {@code infra/grafana/trustledger-dashboard.json} stays valid and in step with the code. Every panel reads
 * the provisioned Prometheus datasource, and the queries cover each required metric under the name Prometheus gives
 * the Micrometer meter (dots to underscores, {@code _total} for counters, {@code _seconds} for timers). Renaming a
 * meter without fixing the dashboard fails here, not in the demo.
 */
class GrafanaDashboardTest {

    private static final Path DASHBOARD = Path.of("..", "infra", "grafana", "trustledger-dashboard.json");
    private static final String AML_ALERTS = "trustledger.aml.alerts"; // risk-aml-service's AmlAlertMetrics.ALERTS

    private final JsonNode dashboard = read();

    private static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DASHBOARD));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + DASHBOARD.toAbsolutePath(), e);
        }
    }

    @Test
    void hasAStableUidForLinks() {
        assertThat(dashboard.get("uid").asString()).isEqualTo("trustledger-overview");
        assertThat(dashboard.get("title").asString()).isEqualTo("TrustLedger overview");
    }

    @Test
    void everyQueryPanelUsesTheProvisionedPrometheusDatasource() {
        assertThat(queryPanels()).isNotEmpty().allSatisfy(panel -> {
            assertThat(panel.get("datasource").get("uid").asString()).as(panel.get("title").asString())
                    .isEqualTo("prometheus");
            assertThat(panel.get("targets")).as(panel.get("title").asString()).isNotEmpty();
        });
    }

    @Test
    void panelIdsAreUnique() {
        List<Integer> ids = new ArrayList<>();
        dashboard.get("panels").forEach(panel -> ids.add(panel.get("id").asInt()));
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void theQueriesCoverEveryRequiredMetric() {
        String queries = String.join("\n", expressions());

        assertThat(queries).contains(
                "http_server_requests_seconds_count",
                "http_server_requests_seconds_bucket",
                prometheusCounter(PaymentsMetrics.TRANSFERS),
                prometheus(PaymentsMetrics.TRANSFER_AMOUNT) + "_bucket",
                prometheusCounter(PaymentsMetrics.TOPUPS),
                prometheusCounter(AML_ALERTS),
                prometheusCounter(ReconciliationService.MISMATCH_METRIC),
                prometheus("trustledger.gateway.circuit.state"));
    }

    @Test
    void latencyPanelsComputeP95AndP99PerEndpointAndErrorRateUsesTheOutcome() {
        String queries = String.join("\n", expressions());

        assertThat(queries).contains("histogram_quantile(0.95, sum by (le, job, method, uri)",
                "histogram_quantile(0.99, sum by (le, job, method, uri)", "outcome=\"SERVER_ERROR\"");
    }

    @Test
    void businessPanelsBreakDownByTheirTag() {
        String queries = String.join("\n", expressions());

        assertThat(queries).contains("sum by (status) (increase(trustledger_transfers_total",
                "sum by (status) (increase(trustledger_topups_total",
                "sum by (rule) (increase(trustledger_aml_alerts_total",
                "sum by (reason) (increase(trustledger_reconciliation_mismatches_total");
    }

    @Test
    void theCircuitPanelAggregatesEachStateBeforeCombiningThem() {
        // Each state is its own series (state label), so "OPEN + HALF_OPEN" without aggregating first matches
        // nothing and the panel stays empty. Found against the real LGTM; kept as a regression check.
        assertThat(expressions()).anySatisfy(expr -> assertThat(expr)
                .contains("2 * max by (name) (trustledger_gateway_circuit_state{state=~\"OPEN|FORCED_OPEN\"})")
                .contains("+ max by (name) (trustledger_gateway_circuit_state{state=\"HALF_OPEN\"})"));
    }

    private List<JsonNode> queryPanels() {
        List<JsonNode> panels = new ArrayList<>();
        dashboard.get("panels").forEach(panel -> {
            if (!"row".equals(panel.get("type").asString())) {
                panels.add(panel);
            }
        });
        return panels;
    }

    private List<String> expressions() {
        List<String> expressions = new ArrayList<>();
        queryPanels().forEach(panel -> panel.get("targets").forEach(target ->
                expressions.add(target.get("expr").asString())));
        return expressions;
    }

    private static String prometheus(String micrometerName) {
        return micrometerName.replace('.', '_');
    }

    private static String prometheusCounter(String micrometerName) {
        return prometheus(micrometerName) + "_total";
    }
}
