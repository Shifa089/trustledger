package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.audit.AuditSnapshotSource;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the audit trail records about a wallet for an {@code @Audited} freeze or unfreeze (FR-A-1): status and
 * tier. Not the balance (not what the decision is about) and not the freeze reason (free text, kept in
 * {@code wallet_freezes}).
 */
@Service
class WalletAuditSnapshotSource implements AuditSnapshotSource {

    private final WalletRepository wallets;

    WalletAuditSnapshotSource(WalletRepository wallets) {
        this.wallets = wallets;
    }

    @Override
    public String entityType() {
        return "WALLET";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> snapshot(UUID id) {
        return wallets.findById(id)
                .map(wallet -> Map.of("status", wallet.getStatus().name(), "tier", wallet.getTier().name()));
    }
}
