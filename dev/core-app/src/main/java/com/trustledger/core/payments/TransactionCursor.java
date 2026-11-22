package com.trustledger.core.payments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A position in a history read newest first (keyset pagination): the last row already returned. The next slice
 * continues with the rows strictly older than it ({@code (createdAt, id) < (cursor)}).
 */
public record TransactionCursor(Instant createdAt, UUID id) {

    public TransactionCursor {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(id, "id");
    }

    public static TransactionCursor of(WalletTransactionRecord row) {
        return new TransactionCursor(row.createdAt(), row.id());
    }
}
