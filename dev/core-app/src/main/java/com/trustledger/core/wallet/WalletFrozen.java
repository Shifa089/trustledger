package com.trustledger.core.wallet;

import com.trustledger.core.shared.EventMetadata;
import com.trustledger.events.WalletFrozenV1;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A wallet was frozen (FR-W-3). Notifications e-mail the user (FR-N-1, needs {@code userId}); AML learns about it
 * through Kafka ({@link #toV1()}). The officer's free-text reason is left out on purpose: it may quote personal data.
 */
public record WalletFrozen(UUID walletId, UUID userId, FreezeSource source, Instant frozenAt,
        EventMetadata metadata) {

    public WalletFrozen {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(frozenAt, "frozenAt");
        Objects.requireNonNull(metadata, "metadata");
    }

    /**
     * The Kafka contract (M8, ADR 0016 addendum). The user ID stays inside core-app: AML works per wallet.
     * Pure, so resubmission-safe.
     */
    public WalletFrozenV1 toV1() {
        return new WalletFrozenV1(metadata.eventId(), frozenAt, walletId, source.name());
    }
}
