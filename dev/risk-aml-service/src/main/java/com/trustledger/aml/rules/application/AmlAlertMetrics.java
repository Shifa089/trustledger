package com.trustledger.aml.rules.application;

import com.trustledger.aml.rules.domain.AmlRule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code trustledger.aml.alerts{rule,severity}} (M12; Prometheus {@code trustledger_aml_alerts_total}): alerts the
 * {@link RuleEngine} raised. Counted after the commit, so an evaluation that rolls back (and is retried by Kafka) is
 * counted once, when it finally sticks. Every configured rule is registered at start, so the dashboard shows a 0
 * for a rule that never fired instead of no series at all. Tags are rule codes and severities, never wallet IDs.
 */
@Component
public class AmlAlertMetrics {

    public static final String ALERTS = "trustledger.aml.alerts";

    private final MeterRegistry meters;

    public AmlAlertMetrics(MeterRegistry meters, List<AmlRule> rules) {
        this.meters = meters;
        rules.forEach(rule -> counter(rule.code(), rule.severity().name()));
    }

    void alertRaised(String ruleCode, String severity) {
        Counter counter = counter(ruleCode, severity);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            counter.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }

    private Counter counter(String ruleCode, String severity) {
        return Counter.builder(ALERTS)
                .description("AML alerts raised, per rule")
                .tag("rule", ruleCode)
                .tag("severity", severity)
                .register(meters);
    }
}
