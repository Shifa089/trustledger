package com.trustledger.core.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter(new AesGcmCipher(TestKeys.KEY_A));

    @Test
    void nullStaysNullInBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void storesCiphertextAndReadsBackPlaintext() {
        String column = converter.convertToDatabaseColumn("ABCDE1234F");

        assertThat(column).isNotEqualTo("ABCDE1234F").doesNotContain("1234");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo("ABCDE1234F");
    }
}
