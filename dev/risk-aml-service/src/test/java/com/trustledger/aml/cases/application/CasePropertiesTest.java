package com.trustledger.aml.cases.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Startup fails on unsafe relay and case settings. */
class CasePropertiesTest {

    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void theDefaultsAreAccepted() {
        assertThatCode(() -> new FreezeRelayProperties(SECOND, 50, Duration.ofSeconds(10))).doesNotThrowAnyException();
        assertThatCode(() -> new CaseProperties(Duration.ofHours(24), 500, 100)).doesNotThrowAnyException();
    }

    @Test
    void relaySettingsOutOfRangeFailStartup() {
        assertThatThrownBy(() -> new FreezeRelayProperties(Duration.ZERO, 50, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreezeRelayProperties(Duration.ofMinutes(2), 50, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreezeRelayProperties(SECOND, 0, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreezeRelayProperties(SECOND, 501, SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreezeRelayProperties(SECOND, 50, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreezeRelayProperties(null, 50, SECOND))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void caseSettingsOutOfRangeFailStartup() {
        assertThatThrownBy(() -> new CaseProperties(Duration.ofDays(-1), 500, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseProperties(Duration.ofDays(32), 500, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseProperties(Duration.ofHours(24), 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseProperties(Duration.ofHours(24), 500, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
