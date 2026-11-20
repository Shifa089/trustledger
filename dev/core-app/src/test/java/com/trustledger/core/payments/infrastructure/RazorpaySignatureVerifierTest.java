package com.trustledger.core.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RazorpaySignatureVerifierTest {

    private static final String SECRET = "fake-webhook-secret-for-tests";
    private static final byte[] BODY = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
    /** Computed independently: printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET". */
    private static final String EXPECTED = "4e6ff2da43d85c90a8fa4fa7c1d606a3c99a5a7d9762783fdd5785a379bfbbe5";

    private final RazorpaySignatureVerifier verifier = RazorpaySignatureVerifier.forSecret(SECRET);

    @Test
    void matchesAnIndependentlyComputedHmac() {
        assertThat(verifier.sign(BODY)).isEqualTo(EXPECTED);
        assertThat(verifier.isValid(BODY, EXPECTED)).isTrue();
    }

    @Test
    void upperCaseHexIsTheSameSignature() {
        assertThat(verifier.isValid(BODY, EXPECTED.toUpperCase(Locale.ROOT))).isTrue();
    }

    @Test
    void anotherSecretDoesNotMatch() {
        assertThat(RazorpaySignatureVerifier.forSecret("another-secret").isValid(BODY, EXPECTED)).isFalse();
    }

    @Test
    void oneChangedByteInTheBodyDoesNotMatch() {
        byte[] tampered = BODY.clone();
        tampered[tampered.length - 3] ^= 1;
        assertThat(verifier.isValid(tampered, EXPECTED)).isFalse();
    }

    /** Why the raw body matters: the same JSON with different spacing is a different message. */
    @Test
    void reSerialisedJsonDoesNotMatch() {
        byte[] spaced = "{\"event\": \"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.isValid(spaced, EXPECTED)).isFalse();
    }

    @Test
    void oneChangedCharacterInTheSignatureDoesNotMatch() {
        String wrong = (EXPECTED.charAt(0) == 'a' ? "b" : "a") + EXPECTED.substring(1);
        assertThat(verifier.isValid(BODY, wrong)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc",
            // right length, not hex
            "zz6ff2da43d85c90a8fa4fa7c1d606a3c99a5a7d9762783fdd5785a379bfbbe5",
            // 63 and 65 characters
            "4e6ff2da43d85c90a8fa4fa7c1d606a3c99a5a7d9762783fdd5785a379bfbbe",
            "4e6ff2da43d85c90a8fa4fa7c1d606a3c99a5a7d9762783fdd5785a379bfbbe50",
            // a prefix such as "sha256=" is not Razorpay's format
            "sha256=4e6ff2da43d85c90a8fa4fa7c1d606a3c99a5a7d9762783fdd5785a379bfbbe5"})
    void malformedSignaturesAreInvalidWithoutThrowing(String signature) {
        assertThat(verifier.isValid(BODY, signature)).isFalse();
    }

    @Test
    void nullBodyIsInvalid() {
        assertThat(verifier.isValid(null, EXPECTED)).isFalse();
    }

    @Test
    void emptyBodyCanBeSignedLikeAnyOther() {
        byte[] empty = new byte[0];
        assertThat(verifier.isValid(empty, verifier.sign(empty))).isTrue();
        assertThat(verifier.isValid(empty, EXPECTED)).isFalse();
    }
}
