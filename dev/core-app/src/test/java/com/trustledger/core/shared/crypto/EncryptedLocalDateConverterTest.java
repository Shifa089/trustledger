package com.trustledger.core.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EncryptedLocalDateConverterTest {

    private final EncryptedLocalDateConverter converter =
            new EncryptedLocalDateConverter(new AesGcmCipher(TestKeys.KEY_A));

    @Test
    void nullStaysNullInBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void storesCiphertextAndReadsBackTheDate() {
        LocalDate dob = LocalDate.of(1995, 4, 15);

        String column = converter.convertToDatabaseColumn(dob);

        assertThat(column).doesNotContain("1995", "04-15");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(dob);
    }

    @Test
    void sameDateEncryptsDifferentlyEachTime() {
        LocalDate dob = LocalDate.of(1995, 4, 15);

        assertThat(converter.convertToDatabaseColumn(dob)).isNotEqualTo(converter.convertToDatabaseColumn(dob));
    }
}
