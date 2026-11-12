package com.trustledger.core.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CryptoPropertiesTest {

    private static final String KEY_16_BYTES = Base64.getEncoder().encodeToString(new byte[16]);
    private static final String KEY_64_BYTES = Base64.getEncoder().encodeToString(new byte[64]);

    @Test
    void acceptsTwoDifferent32ByteKeys() {
        var properties = new CryptoProperties(TestKeys.KEY_A_B64, TestKeys.KEY_B_B64);
        assertThat(properties.encryptionKeyBytes()).isEqualTo(TestKeys.KEY_A);
        assertThat(properties.indexKeyBytes()).isEqualTo(TestKeys.KEY_B);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "change-me", "not*base64"})
    void rejectsMissingOrInvalidEncryptionKey(String key) {
        assertThatThrownBy(() -> new CryptoProperties(key, TestKeys.KEY_B_B64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustledger.crypto.key");
    }

    @Test
    void rejectsMissingIndexKey() {
        assertThatThrownBy(() -> new CryptoProperties(TestKeys.KEY_A_B64, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustledger.crypto.index-key");
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThatThrownBy(() -> new CryptoProperties(KEY_16_BYTES, TestKeys.KEY_B_B64))
                .hasMessageContaining("32 bytes, got 16");
        assertThatThrownBy(() -> new CryptoProperties(TestKeys.KEY_A_B64, KEY_64_BYTES))
                .hasMessageContaining("32 bytes, got 64");
    }

    @Test
    void rejectsReusingTheSameKeyForBothPurposes() {
        assertThatThrownBy(() -> new CryptoProperties(TestKeys.KEY_A_B64, TestKeys.KEY_A_B64))
                .hasMessageContaining("must be different");
    }

    @Test
    void toStringNeverRevealsKeys() {
        var properties = new CryptoProperties(TestKeys.KEY_A_B64, TestKeys.KEY_B_B64);
        assertThat(properties.toString()).doesNotContain(TestKeys.KEY_A_B64).doesNotContain(TestKeys.KEY_B_B64);
    }
}
