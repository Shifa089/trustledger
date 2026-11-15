package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.domain.Wallet;
import java.util.UUID;

/**
 * {@code GET /wallets/me}: amounts as decimal strings (SPEC §6). "Limits used" (spent today, added this month)
 * needs transfers and top-ups and arrives with M6/M7.
 */
public record MyWalletView(UUID walletId, WalletTier tier, WalletStatus status, String currency, String balance,
        String held, String available, Limits limits) {

    /** {@code dailyTransfer} and {@code highValueThreshold} are {@code null} when the tier can't send P2P. */
    public record Limits(String maxBalance, String monthlyLoad, boolean p2pAllowed, String dailyTransfer,
            String highValueThreshold) {

        static Limits of(WalletLimits limits) {
            return new Limits(limits.maxBalance().toDecimalString(), limits.monthlyLoad().toDecimalString(),
                    limits.p2pAllowed(), decimal(limits.dailyTransfer()), decimal(limits.highValueThreshold()));
        }
    }

    static MyWalletView of(Wallet wallet, WalletLimits limits) {
        return new MyWalletView(wallet.getId(), wallet.getTier(), wallet.getStatus(),
                wallet.getCurrency().getCurrencyCode(), wallet.balance().toDecimalString(),
                wallet.held().toDecimalString(), wallet.available().toDecimalString(), Limits.of(limits));
    }

    private static String decimal(Money money) {
        return money == null ? null : money.toDecimalString();
    }
}
