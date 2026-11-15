package com.trustledger.core.wallet;

import java.util.Objects;
import java.util.UUID;

/**
 * A wallet was frozen (FR-W-3). Notifications e-mail the user (FR-N-1). The officer's free-text reason is
 * left out on purpose: it may quote personal data.
 */
public record WalletFrozen(UUID walletId, UUID userId, FreezeSource source) {

    public WalletFrozen {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(source, "source");
    }
}
