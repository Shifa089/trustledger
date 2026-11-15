package com.trustledger.core.kyc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KycPropertiesTest {

    @Test
    void acceptsTheLegalAgeAndAbove() {
        assertThat(new KycProperties(18).minAgeYears()).isEqualTo(18);
        assertThat(new KycProperties(21).minAgeYears()).isEqualTo(21);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 17, 101})
    void belowLegalAgeOrAbsurdValueFailsStartup(int years) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KycProperties(years))
                .withMessageContaining("trustledger.kyc.min-age-years");
    }
}
