package com.trustledger.core.payments;

import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of a wallet's transaction history (FR-S-1), as another module may see it.
 *
 * @param id                   the transfer or top-up ID
 * @param status               the payments status name, e.g. {@code COMPLETED}, {@code HELD_FOR_REVIEW}, {@code CAPTURED}
 * @param counterpartyWalletId the other wallet of a transfer (a wallet ID only, nothing about the person);
 *                             {@code null} for a top-up
 * @param note                 the sender's free text of a transfer (may hold personal data: never logged);
 *                             {@code null} for a top-up
 */
public record WalletTransactionRecord(UUID id, WalletTransactionType type, String status, Money amount,
        UUID counterpartyWalletId, String note, Instant createdAt) {

    public WalletTransactionRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The note is user-written free text and may hold personal data: never printed. */
    @Override
    public String toString() {
        return "WalletTransactionRecord[id=" + id + ", type=" + type + ", status=" + status + ", amount=" + amount
                + ", createdAt=" + createdAt + "]";
    }
}
