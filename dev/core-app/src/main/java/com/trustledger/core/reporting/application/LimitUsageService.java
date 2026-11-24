package com.trustledger.core.reporting.application;

import com.trustledger.core.payments.LimitUsage;
import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * SPEC §5 "limits used" for the caller's own wallet: {@code GET /wallets/me/limit-usage}. The wallet is found from the
 * caller (BOLA), the sums come from {@link PaymentsApi#limitUsage}, which counts them exactly like the limit checks.
 * The limits themselves are in {@code GET /wallets/me}: the wallet module can't read payments (payments depends on
 * wallet), so the usage lives here, next to the history.
 * <p>
 * No {@code @Transactional}: it writes nothing, and {@link PaymentsApi#limitUsage} reads in its own transaction.
 */
@Service
public class LimitUsageService {

    private final WalletApi wallets;
    private final PaymentsApi payments;

    public LimitUsageService(WalletApi wallets, PaymentsApi payments) {
        this.wallets = wallets;
        this.payments = payments;
    }

    /** @throws TrustLedgerException {@code KYC_REQUIRED} when the caller has no wallet yet (as {@code /wallets/me}) */
    public LimitUsage mine(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        UUID walletId = wallets.findByUser(userId).map(WalletView::walletId).orElseThrow(
                () -> new TrustLedgerException(ErrorCode.KYC_REQUIRED, "Complete minimum KYC to get a wallet."));
        return payments.limitUsage(walletId);
    }
}
