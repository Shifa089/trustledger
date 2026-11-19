package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher(JsonMapper.builder().build());

    private String hash(String json) {
        return hasher.hash(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void hashIsLowerCaseSha256Hex() {
        assertThat(hash("{\"a\":1}")).matches("[0-9a-f]{64}");
        // SHA-256 of the empty string.
        assertThat(hasher.hash(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void keyOrderAndWhitespaceDoNotMatter() {
        String a = hash("{\"amount\":\"100.00\",\"recipientMobile\":\"9876543210\",\"note\":\"rent\"}");
        String b = hash("""
                {
                  "note" : "rent",
                  "recipientMobile": "9876543210",
                  "amount":   "100.00"
                }""");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void nestedObjectsAreSortedToo() {
        assertThat(hash("{\"x\":{\"b\":1,\"a\":[{\"d\":1,\"c\":2}]}}"))
                .isEqualTo(hash("{\"x\":{\"a\":[{\"c\":2,\"d\":1}],\"b\":1}}"));
        assertThat(hasher.canonicalText("{\"x\":{\"b\":1,\"a\":2}}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("{\"x\":{\"a\":2,\"b\":1}}");
    }

    @Test
    void anyChangedValueChangesTheHash() {
        String base = hash("{\"amount\":\"100.00\",\"recipientMobile\":\"9876543210\"}");

        assertThat(hash("{\"amount\":\"100.01\",\"recipientMobile\":\"9876543210\"}")).isNotEqualTo(base);
        assertThat(hash("{\"amount\":\"100.00\",\"recipientMobile\":\"9876543211\"}")).isNotEqualTo(base);
        assertThat(hash("{\"amount\":\"100.00\",\"recipientMobile\":\"9876543210\",\"note\":null}"))
                .isNotEqualTo(base);
    }

    @Test
    void arrayOrderMatters() {
        assertThat(hash("[1,2]")).isNotEqualTo(hash("[2,1]"));
    }

    @Test
    void decimalNumbersKeepTheirScale() {
        assertThat(hash("{\"n\":1.50}")).isNotEqualTo(hash("{\"n\":1.5}"));
        assertThat(hasher.canonicalText("{\"n\":1.50}".getBytes(StandardCharsets.UTF_8))).isEqualTo("{\"n\":1.50}");
    }

    @Test
    void invalidJsonIsAValidationError() {
        assertThatThrownBy(() -> hash("{\"amount\":")).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> hash("amount=1")).isInstanceOf(TrustLedgerException.class);
    }
}
