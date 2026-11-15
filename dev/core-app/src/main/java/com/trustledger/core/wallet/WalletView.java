package com.trustledger.core.wallet;

import com.trustledger.core.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A snapshot of a wallet for other modules. The entity never leaves the wallet module.
 *
 * @param available balance minus held (FR-W-2): what the user can spend or transfer
 */
public record WalletView(UUID walletId, UUID userId, WalletTier tier, WalletStatus status, Money balance,
        Money held, Money available) {

    public WalletView {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(available, "available");
    }

    public boolean isActive() {
        return status == WalletStatus.ACTIVE;
    }
}
