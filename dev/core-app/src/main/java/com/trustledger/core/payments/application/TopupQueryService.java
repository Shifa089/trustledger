package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /topups/{id}}: only the owner of the wallet may see a top-up. */
@Service
public class TopupQueryService {

    private final TopupRepository topups;
    private final WalletApi wallets;
    private final PaymentGateway gateway;

    TopupQueryService(TopupRepository topups, WalletApi wallets, PaymentGateway gateway) {
        this.topups = topups;
        this.wallets = wallets;
        this.gateway = gateway;
    }

    /**
     * Someone else's top-up gives the <em>same</em> 404 as an unknown ID, so top-up IDs can't be probed (BOLA).
     */
    @Transactional(readOnly = true)
    public TopupResult get(UUID callerUserId, UUID topupId) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(topupId, "topupId");
        UUID callerWallet = wallets.findByUser(callerUserId).map(WalletView::walletId).orElse(null);
        return topups.findById(topupId)
                .filter(topup -> callerWallet != null && topup.belongsTo(callerWallet))
                .map(topup -> TopupResult.of(topup, gateway.checkoutKeyId()))
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Top-up not found."));
    }
}
