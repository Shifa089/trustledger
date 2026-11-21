package com.trustledger.core.payments;

import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.events.TopupCapturedV1;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A captured top-up was credited to a wallet (FR-P-4): the journal entry GATEWAY_CLEARING -> WALLET is posted.
 * Published inside the crediting transaction, so Spring Modulith records it atomically with the money movement.
 * Consumed by AML (FR-AML-1) through Kafka ({@link #toV1()}). IDs and amount only: no personal data.
 * <p>
 * Not published for a top-up PARKED in suspense (ADR 0006): that money never reached the wallet.
 */
public record TopupCaptured(UUID topupId, UUID walletId, Money amount, UUID journalEntryId, Instant capturedAt,
        EventMetadata metadata) {

    public TopupCaptured {
        Objects.requireNonNull(topupId, "topupId");
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(metadata, "metadata");
    }

    /** The Kafka contract (M8, ADR 0016 addendum). The journal entry stays internal. Pure, so resubmission-safe. */
    public TopupCapturedV1 toV1() {
        return new TopupCapturedV1(metadata.eventId(), capturedAt, walletId, topupId, amount.amountMinor(),
                amount.currency().getCurrencyCode());
    }
}
