package com.trustledger.core.privacy.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.PersonalDataExport;
import com.trustledger.core.kyc.KycApi;
import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Collects one user's data through each module's public API (CLAUDE.md: cross-module calls only via the module's
 * Api). Each call is its own short read-only transaction in the owning module; nothing here holds a lock.
 */
@Component
class DataExportAssembler {

    private final IdentityApi identity;
    private final KycApi kyc;
    private final WalletApi wallets;
    private final PaymentsApi payments;
    private final Clock clock;

    DataExportAssembler(IdentityApi identity, KycApi kyc, WalletApi wallets, PaymentsApi payments, Clock clock) {
        this.identity = identity;
        this.kyc = kyc;
        this.wallets = wallets;
        this.payments = payments;
        this.clock = clock;
    }

    /** @throws TrustLedgerException {@code NOT_FOUND} if the user no longer exists */
    DataExportDocument assemble(UUID requestId, UUID userId) {
        PersonalDataExport person = identity.exportPersonalData(userId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "User not found."));
        WalletView wallet = wallets.findByUser(userId).orElse(null);
        return DataExportDocument.of(requestId, clock.instant(), person, kyc.statusOf(userId), wallet,
                wallet == null ? List.of() : payments.transfersOf(wallet.walletId()),
                wallet == null ? List.of() : payments.topupsOf(wallet.walletId()));
    }
}
