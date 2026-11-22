package com.trustledger.core.privacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrivacyPropertiesTest {

    @Test
    void acceptsTheConfiguredValues() {
        PrivacyProperties properties = new PrivacyProperties(Duration.ofMinutes(10), 50);

        assertThat(properties.exportLinkTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.maxListedRequests()).isEqualTo(50);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT0.5S", "PT10M1S", "PT1H", "-PT1M"})
    void refusesALinkTtlOutsideOneSecondToTenMinutes(String ttl) {
        assertThatThrownBy(() -> new PrivacyProperties(Duration.parse(ttl), 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("export-link-ttl");
    }

    @Test
    void refusesAMissingLinkTtl() {
        assertThatThrownBy(() -> new PrivacyProperties(null, 50)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 201})
    void refusesAListSizeOutsideOneTo200(int size) {
        assertThatThrownBy(() -> new PrivacyProperties(Duration.ofMinutes(10), size))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-listed-requests");
    }
}
