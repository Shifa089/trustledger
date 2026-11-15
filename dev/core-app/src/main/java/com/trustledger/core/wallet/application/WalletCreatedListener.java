package com.trustledger.core.wallet.application;

import com.trustledger.core.wallet.WalletCreated;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * M5: a new wallet gets its {@code WALLET:<id>} ledger account. The listener lives in the wallet module and
 * calls {@link com.trustledger.core.ledger.LedgerApi}, so the dependency points wallet → ledger and the ledger
 * stays free of business-module dependencies.
 * <p>
 * {@link ApplicationModuleListener}: runs after the wallet's transaction has committed, in a transaction of its
 * own. Until the event publication registry arrives (M8), a failure here leaves the wallet unlinked and is not
 * retried; {@link WalletLedgerLinkService#link} is idempotent, so a retry or a backfill is safe.
 */
@Component
class WalletCreatedListener {

    private final WalletLedgerLinkService links;

    WalletCreatedListener(WalletLedgerLinkService links) {
        this.links = links;
    }

    @ApplicationModuleListener
    void on(WalletCreated event) {
        links.link(event.walletId());
    }
}
