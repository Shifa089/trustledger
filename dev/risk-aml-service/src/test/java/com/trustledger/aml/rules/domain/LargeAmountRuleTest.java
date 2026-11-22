package com.trustledger.aml.rules.domain;

import static com.trustledger.aml.rules.domain.RuleFixtures.PROPERTIES;
import static com.trustledger.aml.rules.domain.RuleFixtures.WALLET;
import static com.trustledger.aml.rules.domain.RuleFixtures.incoming;
import static com.trustledger.aml.rules.domain.RuleFixtures.outgoing;
import static com.trustledger.aml.rules.domain.RuleFixtures.topup;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LargeAmountRuleTest {

    private final LargeAmountRule rule = new LargeAmountRule(PROPERTIES);

    @Test
    void isR3MediumAndAlertsOnEveryLargeTransaction() {
        assertThat(rule.code()).isEqualTo("R3");
        assertThat(rule.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(rule.window()).isEmpty();
    }

    @Test
    void justBelowOneLakhDoesNotFire() {
        assertThat(rule.evaluate(WALLET, outgoing(9_999_999))).isEmpty();
    }

    @Test
    void oneLakhFires() {
        assertThat(rule.evaluate(WALLET, outgoing(10_000_000)))
                .hasValueSatisfying(details -> assertThat(details.description())
                        .startsWith("TRANSFER ")
                        .endsWith(" of 100000.00 (at least 100000.00)"));
    }

    @Test
    void aLargeTopupFires() {
        assertThat(rule.evaluate(WALLET, topup(10_000_000))).isPresent();
    }

    @Test
    void aLargeIncomingTransferNeverAlertsTheReceiver() {
        assertThat(rule.evaluate(WALLET, incoming(50_000_000))).isEmpty();
    }
}
