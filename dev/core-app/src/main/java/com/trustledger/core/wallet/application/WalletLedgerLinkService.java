package com.trustledger.core.wallet.application;

import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M5: gives a wallet its ledger account. The wallet module calls the ledger, never the other way round, so the
 * ledger depends on no other business module (CLAUDE.md).
 * <p>
 * Idempotent and race-free: the wallet row is locked first, so two calls for the same wallet run one after the
 * other, and the second finds the link already there.
 */
@Service
public class WalletLedgerLinkService {

    private static final Logger log = LoggerFactory.getLogger(WalletLedgerLinkService.class);

    private final WalletRepository wallets;
    private final LedgerApi ledger;
    private final Clock clock;

    public WalletLedgerLinkService(WalletRepository wallets, LedgerApi ledger, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Opens {@code WALLET:<walletId>} if needed and stores its ID on the wallet, in one transaction.
     *
     * @return the ledger account's ID
     * @throws IllegalStateException if the wallet doesn't exist (the event that triggers this is published only
     *         after the wallet was saved)
     */
    @Transactional
    public UUID link(UUID walletId) {
        Wallet wallet = wallets.lockById(walletId)
                .orElseThrow(() -> new IllegalStateException("Wallet " + walletId + " does not exist"));
        if (wallet.getLedgerAccountId() != null) {
            return wallet.getLedgerAccountId();
        }
        UUID accountId = ledger.openWalletAccount(walletId, wallet.getCurrency());
        wallet.linkLedgerAccount(accountId, clock.instant());
        wallets.saveAndFlush(wallet);
        log.info("Wallet {} linked to ledger account {}", walletId, accountId);
        return accountId;
    }
}
