package com.trustledger.core.wallet.application;

import com.trustledger.core.wallet.WalletView;
import com.trustledger.core.wallet.domain.Wallet;

/** Entity to public snapshot. */
final class WalletViews {

    private WalletViews() {
    }

    static WalletView of(Wallet wallet) {
        return new WalletView(wallet.getId(), wallet.getUserId(), wallet.getTier(), wallet.getStatus(),
                wallet.balance(), wallet.held(), wallet.available());
    }
}
