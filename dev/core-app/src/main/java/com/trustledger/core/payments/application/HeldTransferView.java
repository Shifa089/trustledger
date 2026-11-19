package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer as a compliance officer sees it in the review queue and after a decision. Wallet IDs and the amount
 * only: the customer's free-text note is left out (no personal data in the queue).
 */
public record HeldTransferView(UUID transferId, TransferStatus status, UUID senderWalletId, UUID receiverWalletId,
        Money amount, Instant createdAt, UUID reviewedBy, Instant reviewedAt) {

    static HeldTransferView of(Transfer transfer) {
        return new HeldTransferView(transfer.getId(), transfer.getStatus(), transfer.getSenderWalletId(),
                transfer.getReceiverWalletId(), transfer.amount(), transfer.getCreatedAt(), transfer.getReviewedBy(),
                transfer.getReviewedAt());
    }
}
