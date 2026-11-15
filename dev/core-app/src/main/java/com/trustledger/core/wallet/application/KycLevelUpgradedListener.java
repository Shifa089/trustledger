package com.trustledger.core.wallet.application;

import com.trustledger.core.kyc.KycLevelUpgraded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * FR-W-1: creates or upgrades the wallet when KYC is approved. {@link ApplicationModuleListener} runs after the
 * KYC transaction has committed, in a transaction of its own, so a wallet problem can never undo a KYC
 * approval.
 * <p>
 * Until the event publication registry arrives (M8), an event whose listener fails is not retried; the FULL
 * path opening a wallet directly covers a lost MIN event.
 */
@Component
class KycLevelUpgradedListener {

    private static final Logger log = LoggerFactory.getLogger(KycLevelUpgradedListener.class);

    private final WalletProvisioningService provisioning;

    KycLevelUpgradedListener(WalletProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @ApplicationModuleListener
    void on(KycLevelUpgraded event) {
        try {
            provisioning.apply(event.userId(), event.level());
        } catch (DataIntegrityViolationException raceLost) {
            // Another event for the same user created the wallet between our lookup and our insert.
            // Applying again finds that wallet, and upgrades it if this event's level is higher.
            log.info("Wallet for user {} was created concurrently; applying {} again", event.userId(),
                    event.level());
            provisioning.apply(event.userId(), event.level());
        }
    }
}
