package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** A transfer as the outside sees it. The {@link Transfer} entity never leaves the module. */
public record TransferResult(UUID transferId, TransferStatus status, UUID senderWalletId, UUID receiverWalletId,
        Money amount, String note, Instant createdAt) {

    static TransferResult of(Transfer transfer) {
        return new TransferResult(transfer.getId(), transfer.getStatus(), transfer.getSenderWalletId(),
                transfer.getReceiverWalletId(), transfer.amount(), transfer.getNote(), transfer.getCreatedAt());
    }
}
