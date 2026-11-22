package com.trustledger.aml.rules.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AmlPropertiesTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void theSpecThresholdsAreAccepted() {
        assertThat(RuleFixtures.PROPERTIES.structuring().maxAmountMinor()).isEqualTo(4_999_999);
    }

    @Test
    void everyRuleIsRequired() {
        assertThatThrownBy(() -> new AmlProperties(null, RuleFixtures.PROPERTIES.structuring(),
                RuleFixtures.PROPERTIES.largeAmount(), RuleFixtures.PROPERTIES.fanOut()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("velocity");
    }

    @Test
    void countsAndAmountsMustBePositive() {
        assertThatThrownBy(() -> new AmlProperties.Velocity(0, TEN_MINUTES))
                .hasMessageContaining("velocity.max-outgoing-transfers must be positive");
        assertThatThrownBy(() -> new AmlProperties.Structuring(0, 100, 3, TEN_MINUTES))
                .hasMessageContaining("structuring.min-amount-minor must be positive");
        assertThatThrownBy(() -> new AmlProperties.Structuring(100, 200, 0, TEN_MINUTES))
                .hasMessageContaining("structuring.min-transactions must be positive");
        assertThatThrownBy(() -> new AmlProperties.LargeAmount(0))
                .hasMessageContaining("large-amount.min-amount-minor must be positive");
        assertThatThrownBy(() -> new AmlProperties.FanOut(0, TEN_MINUTES))
                .hasMessageContaining("fan-out.min-distinct-recipients must be positive");
    }

    @Test
    void theStructuringRangeMustNotBeUpsideDown() {
        assertThatThrownBy(() -> new AmlProperties.Structuring(5_000_000, 4_500_000, 3, TEN_MINUTES))
                .hasMessageContaining("max-amount-minor must be at least min-amount-minor");
        assertThat(new AmlProperties.Structuring(100, 100, 1, TEN_MINUTES).maxAmountMinor()).isEqualTo(100);
    }

    @Test
    void windowsMustBePositiveAndAtMost31Days() {
        assertThatThrownBy(() -> new AmlProperties.Velocity(5, null))
                .hasMessageContaining("velocity.window is required");
        assertThatThrownBy(() -> new AmlProperties.Velocity(5, Duration.ZERO))
                .hasMessageContaining("velocity.window must be positive and at most 31 days");
        assertThatThrownBy(() -> new AmlProperties.FanOut(10, Duration.ofDays(32)))
                .hasMessageContaining("fan-out.window must be positive and at most 31 days");
        assertThat(new AmlProperties.FanOut(10, Duration.ofDays(31)).window()).isEqualTo(Duration.ofDays(31));
    }
}
