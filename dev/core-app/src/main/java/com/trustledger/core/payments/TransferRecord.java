package com.trustledger.core.payments;

import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer as another module may see it (see {@link PaymentsApi#transfersOf}). The counterparty is a wallet ID
 * only: nothing about the other person. {@code status} is the payments module's transfer status name, e.g.
 * {@code COMPLETED} or {@code HELD_FOR_REVIEW}. {@code note} is the sender's free text (at most 140 characters).
 */
public record TransferRecord(UUID transferId, UUID senderWalletId, UUID receiverWalletId, Money amount,
        String status, String note, Instant createdAt, Instant updatedAt) {

    /** The note is user-written free text and may hold personal data: never printed. */
    @Override
    public String toString() {
        return "TransferRecord[transferId=" + transferId + ", senderWalletId=" + senderWalletId
                + ", receiverWalletId=" + receiverWalletId + ", amount=" + amount + ", status=" + status + "]";
    }
}
