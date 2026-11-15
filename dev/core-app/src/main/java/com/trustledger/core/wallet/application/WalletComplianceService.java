package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletUnfrozen;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-W-3: compliance officers freeze and unfreeze wallets, with a reason. The wallet row is locked first, so
 * a freeze waits for a running transfer and the next transfer sees FROZEN. The officer comes from the token.
 */
@Service
public class WalletComplianceService {

    private static final Logger log = LoggerFactory.getLogger(WalletComplianceService.class);

    private final WalletRepository wallets;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public WalletComplianceService(WalletRepository wallets, ApplicationEventPublisher events, Clock clock) {
        this.wallets = wallets;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ComplianceWalletView freeze(UUID walletId, UUID officerId, String reason) {
        Wallet wallet = lock(walletId);
        wallet.freeze(reason, FreezeSource.COMPLIANCE, officerId, clock.instant());
        // Flush before publishing: if the database refuses (e.g. a second open freeze), no event goes out.
        wallets.saveAndFlush(wallet);
        events.publishEvent(new WalletFrozen(wallet.getId(), wallet.getUserId(), FreezeSource.COMPLIANCE));
        log.info("Wallet {} frozen by officer {}", walletId, officerId); // the reason may quote personal data
        return ComplianceWalletView.of(wallet);
    }

    @Transactional
    public ComplianceWalletView unfreeze(UUID walletId, UUID officerId, String reason) {
        Wallet wallet = lock(walletId);
        wallet.unfreeze(officerId, reason, clock.instant());
        wallets.saveAndFlush(wallet);
        events.publishEvent(new WalletUnfrozen(wallet.getId(), wallet.getUserId()));
        log.info("Wallet {} unfrozen by officer {}", walletId, officerId);
        return ComplianceWalletView.of(wallet);
    }

    private Wallet lock(UUID walletId) {
        return wallets.lockById(walletId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Wallet not found."));
    }
}
