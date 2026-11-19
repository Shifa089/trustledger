package com.trustledger.core.wallet;

import com.trustledger.core.shared.Money;
import java.util.Objects;

/**
 * The limits of one tier (SPEC §2 limits table, FR-W-2), from {@code trustledger.limits.*}.
 *
 * @param maxBalance         the balance may never exceed this (checked by payments before crediting)
 * @param monthlyLoad        money added per calendar month
 * @param p2pAllowed         whether the tier may send P2P transfers (MIN: no)
 * @param dailyTransfer      outgoing transfers per day; {@code null} when P2P is not allowed
 * @param highValueThreshold transfers at or above it are held for review; {@code null} when P2P is not allowed
 * @param monthlyTransfer    outgoing transfers per calendar month (own policy, FR-P-2); {@code null} when P2P is
 *                           not allowed or no monthly limit is configured
 */
public record WalletLimits(WalletTier tier, Money maxBalance, Money monthlyLoad, boolean p2pAllowed,
        Money dailyTransfer, Money highValueThreshold, Money monthlyTransfer) {

    public WalletLimits {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(maxBalance, "maxBalance");
        Objects.requireNonNull(monthlyLoad, "monthlyLoad");
        if (p2pAllowed != (dailyTransfer != null) || p2pAllowed != (highValueThreshold != null)) {
            throw new IllegalArgumentException(
                    "dailyTransfer and highValueThreshold are set exactly when P2P is allowed");
        }
        if (!p2pAllowed && monthlyTransfer != null) {
            throw new IllegalArgumentException("monthlyTransfer requires P2P to be allowed");
        }
    }
}
