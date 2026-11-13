package com.trustledger.core.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AesGcmCipherTest {

    private final AesGcmCipher cipher = new AesGcmCipher(TestKeys.KEY_A);

    @ParameterizedTest
    @ValueSource(strings = {"ABCDE1234F", "", "Flat 4, MG Road, Bengaluru — 560001", "नमस्ते 🙂"})
    void roundTrips(String plaintext) {
        assertThat(cipher.decrypt(cipher.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void samePlaintextGivesDifferentCiphertextBecauseIvIsRandom() {
        String first = cipher.encrypt("9876543210");
        String second = cipher.encrypt("9876543210");

        assertThat(first).isNotEqualTo(second);
        assertThat(iv(first)).isNotEqualTo(iv(second));
    }

    @Test
    void layoutIsIvPlusCiphertextPlusTag() {
        String plaintext = "ABCDE1234F";
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt(plaintext));

        assertThat(raw).hasSize(12 + plaintext.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("ABCDE1234F"));
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain("ABCDE1234F");
    }

    /** Flip one bit in the IV (0), the ciphertext (12) and the tag (last byte). */
    @ParameterizedTest
    @ValueSource(ints = {0, 11, 12, 15, -1})
    void detectsTampering(int position) {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("ABCDE1234F"));
        int index = position < 0 ? raw.length + position : position;
        raw[index] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Decryption failed");
    }

    @Test
    void wrongKeyFails() {
        String encrypted = cipher.encrypt("ABCDE1234F");
        AesGcmCipher other = new AesGcmCipher(TestKeys.KEY_B);

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64 !!", "AAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void garbageOrTruncatedInputFails(String input) {
        assertThatThrownBy(() -> cipher.decrypt(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Decryption failed");
    }

    @Test
    void rejectsKeyThatIsNot32Bytes() {
        assertThatThrownBy(() -> new AesGcmCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsFromProperties() {
        var properties = new CryptoProperties(TestKeys.KEY_A_B64, TestKeys.KEY_B_B64);
        String encrypted = new AesGcmCipher(properties).encrypt("x");

        assertThat(cipher.decrypt(encrypted)).isEqualTo("x");
    }

    private static String iv(String encoded) {
        return Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(Base64.getDecoder().decode(encoded), 12));
    }
}
