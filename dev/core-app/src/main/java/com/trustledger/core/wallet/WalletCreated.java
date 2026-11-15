package com.trustledger.core.wallet;

import java.util.Objects;
import java.util.UUID;

/** A wallet was opened (FR-W-1). The ledger creates the wallet's account from it (M5). IDs only. */
public record WalletCreated(UUID walletId, UUID userId) {

    public WalletCreated {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(userId, "userId");
    }
}
