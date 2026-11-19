package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code trustledger.limits.*}: the wallet limits table (SPEC §2, FR-W-2). Amounts are decimal strings bound
 * to {@link Money}. Startup fails on a configuration that would be a compliance bug:
 * <ul>
 *   <li>a missing or zero amount;</li>
 *   <li>MIN allowing P2P (small PPIs can't transfer funds);</li>
 *   <li>a P2P tier without a daily transfer limit, or a high-value threshold above it;</li>
 *   <li>MIN limits higher than FULL limits.</li>
 * </ul>
 *
 * @param highValueThreshold transfers at or above it are held for review (FR-P-3)
 */
@ConfigurationProperties("trustledger.limits")
public record LimitsProperties(Money highValueThreshold, TierLimits min, TierLimits full) {

    /**
     * @param dailyTransfer   required when {@code p2pAllowed}, must be absent otherwise
     * @param monthlyTransfer optional (own policy for FR-P-2's monthly limit); only with {@code p2pAllowed}, and
     *                        never below {@code dailyTransfer}
     */
    public record TierLimits(Money maxBalance, Money monthlyLoad, boolean p2pAllowed, Money dailyTransfer,
            Money monthlyTransfer) {

        /** Configuration binding uses this canonical constructor. */
        @ConstructorBinding
        public TierLimits {
        }

        /** A tier without a monthly transfer limit. */
        public TierLimits(Money maxBalance, Money monthlyLoad, boolean p2pAllowed, Money dailyTransfer) {
            this(maxBalance, monthlyLoad, p2pAllowed, dailyTransfer, null);
        }

        void validate(String tier) {
            requirePositive("trustledger.limits." + tier + ".max-balance", maxBalance);
            requirePositive("trustledger.limits." + tier + ".monthly-load", monthlyLoad);
            if (p2pAllowed) {
                requirePositive("trustledger.limits." + tier + ".daily-transfer", dailyTransfer);
            } else if (dailyTransfer != null) {
                throw new IllegalArgumentException("trustledger.limits." + tier
                        + ".daily-transfer must not be set when p2p-allowed is false");
            }
            if (monthlyTransfer != null) {
                if (!p2pAllowed) {
                    throw new IllegalArgumentException("trustledger.limits." + tier
                            + ".monthly-transfer must not be set when p2p-allowed is false");
                }
                requirePositive("trustledger.limits." + tier + ".monthly-transfer", monthlyTransfer);
                if (dailyTransfer.isGreaterThan(monthlyTransfer)) {
                    throw new IllegalArgumentException("trustledger.limits." + tier
                            + ".monthly-transfer must not be below daily-transfer");
                }
            }
        }
    }

    public LimitsProperties {
        if (min == null || full == null) {
            throw new IllegalArgumentException("trustledger.limits.min and trustledger.limits.full are required");
        }
        min.validate("min");
        full.validate("full");
        if (min.p2pAllowed()) {
            throw new IllegalArgumentException(
                    "trustledger.limits.min.p2p-allowed must be false: minimum-KYC wallets can't transfer funds");
        }
        if (min.maxBalance().isGreaterThan(full.maxBalance())) {
            throw new IllegalArgumentException("trustledger.limits.min.max-balance must not exceed full.max-balance");
        }
        if (min.monthlyLoad().isGreaterThan(full.monthlyLoad())) {
            throw new IllegalArgumentException("trustledger.limits.min.monthly-load must not exceed full.monthly-load");
        }
        if (full.p2pAllowed()) {
            requirePositive("trustledger.limits.high-value-threshold", highValueThreshold);
            if (highValueThreshold.isGreaterThan(full.dailyTransfer())) {
                throw new IllegalArgumentException(
                        "trustledger.limits.high-value-threshold must not exceed full.daily-transfer");
            }
        }
    }

    /** The limits that apply to a wallet of this tier. */
    public WalletLimits forTier(WalletTier tier) {
        TierLimits limits = switch (tier) {
            case MIN -> min;
            case FULL -> full;
        };
        return new WalletLimits(tier, limits.maxBalance(), limits.monthlyLoad(), limits.p2pAllowed(),
                limits.p2pAllowed() ? limits.dailyTransfer() : null,
                limits.p2pAllowed() ? highValueThreshold : null, limits.monthlyTransfer());
    }

    private static void requirePositive(String property, Money value) {
        if (value == null || value.isZero()) {
            throw new IllegalArgumentException(property + " is required and must be greater than zero");
        }
    }
}
