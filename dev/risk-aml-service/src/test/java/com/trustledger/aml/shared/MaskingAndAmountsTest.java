package com.trustledger.aml.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaskingAndAmountsTest {

    @Test
    void anIdKeepsOnlyItsLastFourCharacters() {
        assertThat(IdMasking.id(UUID.fromString("0193a000-0000-7000-8000-c0ffee5a1b2c"))).isEqualTo("****1b2c");
        assertThat(IdMasking.id(null)).isEmpty();
    }

    @Test
    void everyUuidInsideTextIsMasked() {
        String text = "transfers 0193a000-0000-7000-8000-000000000001 and 0193A000-0000-7000-8000-0000000000AB; R2";

        assertThat(IdMasking.idsIn(text)).isEqualTo("transfers ****0001 and ****00AB; R2");
        assertThat(IdMasking.idsIn("no ids here")).isEqualTo("no ids here");
        assertThat(IdMasking.idsIn(null)).isEmpty();
    }

    @Test
    void paiseBecomeADecimalString() {
        assertThat(Amounts.decimal(4_600_000)).isEqualTo("46000.00");
        assertThat(Amounts.decimal(4_999_999)).isEqualTo("49999.99");
        assertThat(Amounts.decimal(5)).isEqualTo("0.05");
        assertThat(Amounts.decimal(0)).isEqualTo("0.00");
        assertThat(Amounts.decimal(-5)).isEqualTo("-0.05");
        assertThat(Amounts.decimal(Long.MAX_VALUE)).isEqualTo("92233720368547758.07");
    }
}
