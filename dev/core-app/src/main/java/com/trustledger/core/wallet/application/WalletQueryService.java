package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The customer's own wallet (SPEC §5 {@code GET /wallets/me}). */
@Service
public class WalletQueryService {

    private final WalletRepository wallets;
    private final TierLimitsCatalog limits;

    public WalletQueryService(WalletRepository wallets, TierLimitsCatalog limits) {
        this.wallets = wallets;
        this.limits = limits;
    }

    /**
     * 403 {@code KYC_REQUIRED} until minimum KYC has created the wallet.
     * <p>
     * <b>The balance is never cached</b> (ADR 0019): the wallet row is read from PostgreSQL on every call. Only the
     * tier's limits come from the Redis cache ({@link TierLimitsCatalog}).
     */
    @Transactional(readOnly = true)
    public MyWalletView mine(UUID userId) {
        Wallet wallet = wallets.findByUserId(userId).orElseThrow(() -> new TrustLedgerException(
                ErrorCode.KYC_REQUIRED, "Complete minimum KYC to get a wallet."));
        return MyWalletView.of(wallet, limits.forTier(wallet.getTier()));
    }
}
