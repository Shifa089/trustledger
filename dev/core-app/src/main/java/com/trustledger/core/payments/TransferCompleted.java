package com.trustledger.core.payments;

import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A P2P transfer was posted to the ledger ("Transfer flow" step 7). Published inside the transfer's transaction,
 * so Spring Modulith records it in {@code event_publication} atomically with the money movement. Consumed by AML
 * and notifications from M8 (mapped to {@code TransferCompletedV1}). IDs and amount only: no personal data.
 * <p>
 * Not published for a transfer held for review; that one publishes on approval (part 3).
 */
public record TransferCompleted(UUID transferId, UUID senderWalletId, UUID receiverWalletId, Money amount,
        UUID journalEntryId, Instant completedAt) {

    public TransferCompleted {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(senderWalletId, "senderWalletId");
        Objects.requireNonNull(receiverWalletId, "receiverWalletId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
