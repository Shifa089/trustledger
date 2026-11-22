package com.trustledger.core.payments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Which history rows to read (FR-S-1): one wallet, optionally a time window and a type.
 *
 * @param fromInclusive rows created at or after this instant; {@code null} for no lower bound
 * @param toExclusive   rows created before this instant; {@code null} for no upper bound
 * @param type          only this type; {@code null} for all
 */
public record TransactionFilter(UUID walletId, Instant fromInclusive, Instant toExclusive,
        WalletTransactionType type) {

    public TransactionFilter {
        Objects.requireNonNull(walletId, "walletId");
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
    }

    public static TransactionFilter all(UUID walletId) {
        return new TransactionFilter(walletId, null, null, null);
    }
}
