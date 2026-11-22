package com.trustledger.aml.rules.domain;

import static com.trustledger.aml.rules.domain.RuleFixtures.CLOCK;
import static com.trustledger.aml.rules.domain.RuleFixtures.NOW;
import static com.trustledger.aml.rules.domain.RuleFixtures.PROPERTIES;
import static com.trustledger.aml.rules.domain.RuleFixtures.WALLET;
import static com.trustledger.aml.rules.domain.RuleFixtures.incoming;
import static com.trustledger.aml.rules.domain.RuleFixtures.outgoing;
import static com.trustledger.aml.rules.domain.RuleFixtures.topup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StructuringRuleTest {

    private static final Instant SINCE = NOW.minus(Duration.ofHours(24));

    private final FactWindowQueries queries = mock(FactWindowQueries.class);
    private final StructuringRule rule = new StructuringRule(queries, CLOCK, PROPERTIES);

    @Test
    void isR2HighWithA24HourWindow() {
        assertThat(rule.code()).isEqualTo("R2");
        assertThat(rule.severity()).isEqualTo(Severity.HIGH);
        assertThat(rule.window()).contains(Duration.ofHours(24));
    }

    @Test
    void twoTransactionsInTheRangeDoNotFire() {
        when(queries.countInitiatedInAmountRangeSince(WALLET, 4_500_000, 4_999_999, SINCE)).thenReturn(2L);

        assertThat(rule.evaluate(WALLET, outgoing(4_600_000))).isEmpty();
    }

    @Test
    void theThirdTransactionInTheRangeFires() {
        when(queries.countInitiatedInAmountRangeSince(WALLET, 4_500_000, 4_999_999, SINCE)).thenReturn(3L);

        assertThat(rule.evaluate(WALLET, outgoing(4_600_000)))
                .hasValueSatisfying(details -> assertThat(details.description())
                        .startsWith("3 transactions between 45000.00 and 49999.99 in PT24H (at least 3)"));
    }

    @Test
    void bothEndsOfTheRangeAreChecked() {
        when(queries.countInitiatedInAmountRangeSince(WALLET, 4_500_000, 4_999_999, SINCE)).thenReturn(3L);

        assertThat(rule.evaluate(WALLET, outgoing(4_500_000))).isPresent();
        assertThat(rule.evaluate(WALLET, outgoing(4_999_999))).isPresent();
    }

    @Test
    void amountsJustOutsideTheRangeAreNotChecked() {
        assertThat(rule.evaluate(WALLET, outgoing(4_499_999))).isEmpty();
        assertThat(rule.evaluate(WALLET, outgoing(5_000_000))).isEmpty();

        verifyNoInteractions(queries);
    }

    @Test
    void aTopupInTheRangeCounts() {
        when(queries.countInitiatedInAmountRangeSince(WALLET, 4_500_000, 4_999_999, SINCE)).thenReturn(3L);

        assertThat(rule.evaluate(WALLET, topup(4_600_000))).isPresent();
    }

    @Test
    void anIncomingTransferNeverAlertsTheReceiver() {
        when(queries.countInitiatedInAmountRangeSince(any(), anyLong(), anyLong(), any())).thenReturn(99L);

        assertThat(rule.evaluate(WALLET, incoming(4_600_000))).isEmpty();
    }
}
