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

class VelocityRuleTest {

    private final FactWindowQueries queries = mock(FactWindowQueries.class);
    private final VelocityRule rule = new VelocityRule(queries, CLOCK, PROPERTIES);

    @Test
    void isR1MediumWithA10MinuteWindow() {
        assertThat(rule.code()).isEqualTo("R1");
        assertThat(rule.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(rule.window()).contains(Duration.ofMinutes(10));
    }

    @Test
    void fiveOutgoingTransfersIn10MinutesDoNotFire() {
        when(queries.countOutgoingTransfersSince(WALLET, NOW.minus(Duration.ofMinutes(10)))).thenReturn(5L);

        assertThat(rule.evaluate(WALLET, outgoing(100))).isEmpty();
    }

    @Test
    void theSixthOutgoingTransferIn10MinutesFires() {
        when(queries.countOutgoingTransfersSince(WALLET, NOW.minus(Duration.ofMinutes(10)))).thenReturn(6L);

        assertThat(rule.evaluate(WALLET, outgoing(100)))
                .hasValueSatisfying(details -> assertThat(details.description())
                        .startsWith("6 outgoing transfers in PT10M (more than 5)"));
    }

    @Test
    void incomingTransfersAndTopupsAreNotChecked() {
        assertThat(rule.evaluate(WALLET, incoming(100))).isEmpty();
        assertThat(rule.evaluate(WALLET, topup(100))).isEmpty();

        verifyNoInteractions(queries);
    }
}
