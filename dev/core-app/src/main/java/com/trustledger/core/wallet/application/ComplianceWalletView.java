package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.masking.Masking;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletFreeze;
import java.time.Instant;
import java.util.UUID;

/**
 * A wallet as an officer sees it after a freeze or unfreeze: state and the freeze in force, no personal data.
 *
 * @param currentFreeze {@code null} when the wallet is not frozen
 */
public record ComplianceWalletView(UUID walletId, UUID userId, WalletTier tier, WalletStatus status,
        String balance, String held, String available, CurrentFreeze currentFreeze) {

    public record CurrentFreeze(UUID freezeId, FreezeSource source, String reason, UUID frozenBy,
            Instant frozenAt) {

        /** The officer's free text may quote personal data: never in a log line. */
        @Override
        public String toString() {
            return "CurrentFreeze[freezeId=" + freezeId + ", source=" + source + ", reason="
                    + Masking.FULLY_MASKED + ", frozenBy=" + frozenBy + ", frozenAt=" + frozenAt + "]";
        }
    }

    static ComplianceWalletView of(Wallet wallet) {
        CurrentFreeze freeze = wallet.currentFreeze().map(ComplianceWalletView::current).orElse(null);
        return new ComplianceWalletView(wallet.getId(), wallet.getUserId(), wallet.getTier(), wallet.getStatus(),
                wallet.balance().toDecimalString(), wallet.held().toDecimalString(),
                wallet.available().toDecimalString(), freeze);
    }

    private static CurrentFreeze current(WalletFreeze freeze) {
        return new CurrentFreeze(freeze.getId(), freeze.getSource(), freeze.getReason(), freeze.getFrozenBy(),
                freeze.getFrozenAt());
    }
}
