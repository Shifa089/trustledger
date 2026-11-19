package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /transfers/{id}}: only the sender or the receiver may see a transfer. */
@Service
public class TransferQueryService {

    private final TransferRepository transfers;
    private final WalletApi wallets;

    public TransferQueryService(TransferRepository transfers, WalletApi wallets) {
        this.transfers = transfers;
        this.wallets = wallets;
    }

    /**
     * Someone else's transfer gives the <em>same</em> 404 as an unknown ID, so transfer IDs can't be probed
     * (BOLA, and no confirmation that an ID exists).
     */
    @Transactional(readOnly = true)
    public TransferResult get(UUID callerUserId, UUID transferId) {
        Objects.requireNonNull(callerUserId, "callerUserId");
        Objects.requireNonNull(transferId, "transferId");
        UUID callerWallet = wallets.findByUser(callerUserId).map(WalletView::walletId).orElse(null);
        return transfers.findById(transferId)
                .filter(transfer -> callerWallet != null && transfer.involves(callerWallet))
                .map(TransferResult::of)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Transfer not found."));
    }
}
