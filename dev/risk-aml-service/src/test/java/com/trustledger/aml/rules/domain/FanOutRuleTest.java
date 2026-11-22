package com.trustledger.aml.rules.domain;

import static com.trustledger.aml.rules.domain.RuleFixtures.CLOCK;
import static com.trustledger.aml.rules.domain.RuleFixtures.NOW;
import static com.trustledger.aml.rules.domain.RuleFixtures.PROPERTIES;
import static com.trustledger.aml.rules.domain.RuleFixtures.WALLET;
import static com.trustledger.aml.rules.domain.RuleFixtures.incoming;
import static com.trustledger.aml.rules.domain.RuleFixtures.outgoing;
import static com.trustledger.aml.rules.domain.RuleFixtures.topup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FanOutRuleTest {

    private final FactWindowQueries queries = mock(FactWindowQueries.class);
    private final FanOutRule rule = new FanOutRule(queries, CLOCK, PROPERTIES);

    @Test
    void isR4HighWithA24HourWindow() {
        assertThat(rule.code()).isEqualTo("R4");
        assertThat(rule.severity()).isEqualTo(Severity.HIGH);
        assertThat(rule.window()).contains(Duration.ofHours(24));
    }

    @Test
    void nineDistinctRecipientsDoNotFire() {
        when(queries.countDistinctRecipientsSince(WALLET, NOW.minus(Duration.ofHours(24)))).thenReturn(9L);

        assertThat(rule.evaluate(WALLET, outgoing(100))).isEmpty();
    }

    @Test
    void tenDistinctRecipientsFire() {
        when(queries.countDistinctRecipientsSince(WALLET, NOW.minus(Duration.ofHours(24)))).thenReturn(10L);

        assertThat(rule.evaluate(WALLET, outgoing(100)))
                .hasValueSatisfying(details -> assertThat(details.description())
                        .startsWith("transfers to 10 distinct wallets in PT24H (at least 10)"));
    }

    @Test
    void incomingTransfersAndTopupsAreNotChecked() {
        assertThat(rule.evaluate(WALLET, incoming(100))).isEmpty();
        assertThat(rule.evaluate(WALLET, topup(100))).isEmpty();

        verifyNoInteractions(queries);
    }
}
