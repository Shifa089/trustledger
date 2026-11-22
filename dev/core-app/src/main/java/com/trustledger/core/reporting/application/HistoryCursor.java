package com.trustledger.core.reporting.application;

import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The opaque {@code cursor} of the history's keyset mode: base64url of {@code v1|<createdAt ISO>|<id>}. Opaque so
 * clients don't build their own; versioned so the format can change. It holds only a timestamp and a row ID, and
 * every query is still limited to the caller's wallet, so a forged cursor can't reach anyone else's rows. A malformed
 * cursor is 400 {@code VALIDATION_FAILED}.
 */
final class HistoryCursor {

    private static final String VERSION = "v1";

    private HistoryCursor() {
    }

    static String encode(TransactionCursor cursor) {
        String raw = VERSION + "|" + cursor.createdAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static TransactionCursor decode(String token) {
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw invalid();
        }
        try {
            return new TransactionCursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static TrustLedgerException invalid() {
        return new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "cursor is not valid.");
    }
}
