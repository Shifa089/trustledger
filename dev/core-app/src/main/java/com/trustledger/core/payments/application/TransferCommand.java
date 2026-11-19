package com.trustledger.core.payments.application;

import com.trustledger.core.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Input of {@link TransferService#transfer}. There is deliberately no sender wallet ID: the sender's wallet is
 * the caller's own, derived from {@code senderUserId} (taken from the token), so a client can never move money
 * out of someone else's wallet (BOLA).
 *
 * @param note optional, at most 140 characters; blank means none
 */
public record TransferCommand(UUID senderUserId, TransferRecipient recipient, Money amount, String note) {

    public TransferCommand {
        Objects.requireNonNull(senderUserId, "senderUserId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(amount, "amount");
    }

    /** The note may be personal; {@code recipient} masks its own mobile number. */
    @Override
    public String toString() {
        return "TransferCommand[senderUserId=" + senderUserId + ", recipient=" + recipient + ", amount=" + amount
                + ", note=" + (note == null ? "null" : "****") + "]";
    }
}
