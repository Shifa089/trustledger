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
    private final LimitsProperties limits;

    public WalletQueryService(WalletRepository wallets, LimitsProperties limits) {
        this.wallets = wallets;
        this.limits = limits;
    }

    /** 403 {@code KYC_REQUIRED} until minimum KYC has created the wallet. */
    @Transactional(readOnly = true)
    public MyWalletView mine(UUID userId) {
        Wallet wallet = wallets.findByUserId(userId).orElseThrow(() -> new TrustLedgerException(
                ErrorCode.KYC_REQUIRED, "Complete minimum KYC to get a wallet."));
        return MyWalletView.of(wallet, limits.forTier(wallet.getTier()));
    }
}
