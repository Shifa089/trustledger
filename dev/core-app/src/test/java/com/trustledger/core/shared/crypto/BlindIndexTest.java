package com.trustledger.core.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BlindIndexTest {

    private final BlindIndex index = new BlindIndex(TestKeys.KEY_B);

    @Test
    void isDeterministicLowercaseHex() {
        String first = index.email("customer1@example.com");

        assertThat(first).isEqualTo(index.email("customer1@example.com")).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Customer1@Example.COM", "  customer1@example.com  ", "CUSTOMER1@EXAMPLE.COM"})
    void normalisesEmails(String variant) {
        assertThat(index.email(variant)).isEqualTo(index.email("customer1@example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+91 98765 43210", "+91-98765-43210", "919876543210", "09876543210", " 98765 43210 "})
    void normalisesPhones(String variant) {
        assertThat(index.phone(variant)).isEqualTo(index.phone("9876543210"));
    }

    @Test
    void differentValuesGiveDifferentIndexes() {
        assertThat(index.phone("9876543210")).isNotEqualTo(index.phone("9876543211"));
    }

    @Test
    void sameTextInDifferentFieldsGivesDifferentIndexes() {
        assertThat(index.value("pan", "ABCDE1234F")).isNotEqualTo(index.value("account", "ABCDE1234F"));
    }

    @Test
    void differentKeyGivesDifferentIndex() {
        BlindIndex other = new BlindIndex(TestKeys.KEY_A);
        assertThat(other.email("customer1@example.com")).isNotEqualTo(index.email("customer1@example.com"));
    }

    @Test
    void indexDoesNotContainTheValue() {
        assertThat(index.phone("9876543210")).doesNotContain("9876543210");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "98765432101234", "abc", "+1 415 555 0100"})
    void rejectsPhonesThatAreNotTenDigitsAfterNormalising(String phone) {
        assertThatThrownBy(() -> index.phone(phone)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void rejectsBlankInput(String blank) {
        assertThatThrownBy(() -> index.email(blank)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.phone(blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "+91 98765 43210", "09876543210", "98765-43210"})
    void normalisesPhoneToTheTenDigitsTheIndexUses(String phone) {
        assertThat(BlindIndex.normalisePhone(phone)).isEqualTo("9876543210");
        assertThat(index.phone(phone)).isEqualTo(index.phone("9876543210"));
    }
}
