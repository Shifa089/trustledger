package com.trustledger.core.shared.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MaskingTest {

    @Test
    void masksPanKeepingTheDigits() {
        assertThat(Masking.pan("ABCDE1234F")).isEqualTo("XXXXX1234X");
    }

    @Test
    void masksPhoneKeepingLastFour() {
        assertThat(Masking.phone("9876543210")).isEqualTo("******3210");
    }

    @ParameterizedTest
    @CsvSource({
            "samiulla@domain.com, s***@domain.com",
            "a@b.in, a***@b.in",
            "customer.one+tag@example.co.in, c***@example.co.in"
    })
    void masksEmailKeepingFirstLetterAndDomain(String email, String expected) {
        assertThat(Masking.email(email)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "Test Customer One, T*** C*** O***",
            "Samiulla, S***",
            "'  Ravi   Kumar  ', R*** K***",
            "Élodie Āsha, É*** Ā***"
    })
    void masksNameKeepingInitialsOnly(String name, String expected) {
        assertThat(Masking.name(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankNameIsFullyMaskedEvenWhitespace(String name) {
        assertThat(Masking.name(name)).isEqualTo(Masking.FULLY_MASKED);
    }

    @Test
    void nullStaysNull() {
        assertThat(Masking.name(null)).isNull();
        assertThat(Masking.pan(null)).isNull();
        assertThat(Masking.phone(null)).isNull();
        assertThat(Masking.email(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abcde1234f", "ABCDE12345", "ABCDE1234F ", "SECRET"})
    void malformedPanIsFullyMasked(String pan) {
        assertThat(Masking.pan(pan)).isEqualTo(Masking.FULLY_MASKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "98765", "+919876543210", "98765 43210"})
    void malformedPhoneIsFullyMasked(String phone) {
        assertThat(Masking.phone(phone)).isEqualTo(Masking.FULLY_MASKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-at-sign", "@domain.com", "user@", "a@b@c.com"})
    void malformedEmailIsFullyMasked(String email) {
        assertThat(Masking.email(email)).isEqualTo(Masking.FULLY_MASKED);
    }
}
