package com.trustledger.core.payments;

import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.events.TransferCompletedV1;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A P2P transfer was posted to the ledger ("Transfer flow" step 7). Published inside the transfer's transaction,
 * so Spring Modulith records it in {@code event_publication} atomically with the money movement. Consumed by AML
 * through Kafka ({@link #toV1()}) and by notifications. IDs and amount only: no personal data.
 * <p>
 * Not published for a transfer held for review; that one publishes on approval.
 */
public record TransferCompleted(UUID transferId, UUID senderWalletId, UUID receiverWalletId, Money amount,
        UUID journalEntryId, Instant completedAt, EventMetadata metadata) {

    public TransferCompleted {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(senderWalletId, "senderWalletId");
        Objects.requireNonNull(receiverWalletId, "receiverWalletId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(metadata, "metadata");
    }

    /**
     * The Kafka contract (M8). The contract lives in {@code event-contracts}, which can't see this type, so the
     * mapping sits on the source record (ADR 0016 addendum). The sender is the contract's {@code walletId} (the
     * message key); the journal entry stays internal. Pure: mapping a resubmitted event gives the same record.
     */
    public TransferCompletedV1 toV1() {
        return new TransferCompletedV1(metadata.eventId(), completedAt, senderWalletId, receiverWalletId, transferId,
                amount.amountMinor(), amount.currency().getCurrencyCode());
    }
}
