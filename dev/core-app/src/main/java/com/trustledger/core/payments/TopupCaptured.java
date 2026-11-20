package com.trustledger.core.payments;

import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A captured top-up was credited to a wallet (FR-P-4): the journal entry GATEWAY_CLEARING -> WALLET is posted.
 * Published inside the crediting transaction, so Spring Modulith records it atomically with the money movement.
 * Consumed by AML (FR-AML-1) from M8, mapped to {@code TopupCapturedV1}. IDs and amount only: no personal data.
 * <p>
 * Not published for a top-up PARKED in suspense (ADR 0006): that money never reached the wallet.
 */
public record TopupCaptured(UUID topupId, UUID walletId, Money amount, UUID journalEntryId, Instant capturedAt) {

    public TopupCaptured {
        Objects.requireNonNull(topupId, "topupId");
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(capturedAt, "capturedAt");
    }
}
