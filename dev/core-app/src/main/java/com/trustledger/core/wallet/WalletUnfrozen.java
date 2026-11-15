package com.trustledger.core.wallet;

import java.util.Objects;
import java.util.UUID;

/** A frozen wallet is active again (FR-W-3). IDs only. */
public record WalletUnfrozen(UUID walletId, UUID userId) {

    public WalletUnfrozen {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(userId, "userId");
    }
}
