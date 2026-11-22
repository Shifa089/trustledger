package com.trustledger.core.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryCursorTest {

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsWithMicrosecondPrecision() {
        TransactionCursor cursor = new TransactionCursor(Instant.parse("2026-11-22T10:15:30.123456Z"),
                UUID.randomUUID());

        String token = HistoryCursor.encode(cursor);

        assertThat(token).doesNotContain("=", "+", "/");
        assertThat(HistoryCursor.decode(token)).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage!!", "bm90LWEtY3Vyc29y", "%%%"})
    void refusesGarbage(String token) {
        assertInvalid(token);
    }

    @Test
    void refusesAWrongVersionOrShape() {
        assertInvalid(b64("v2|2026-11-22T10:15:30Z|" + UUID.randomUUID()));
        assertInvalid(b64("v1|2026-11-22T10:15:30Z"));
        assertInvalid(b64("v1|yesterday|" + UUID.randomUUID()));
        assertInvalid(b64("v1|2026-11-22T10:15:30Z|not-a-uuid"));
        assertInvalid(b64("v1|2026-11-22T10:15:30Z|" + UUID.randomUUID() + "|extra"));
    }

    private static void assertInvalid(String token) {
        assertThatThrownBy(() -> HistoryCursor.decode(token)).isInstanceOf(TrustLedgerException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
