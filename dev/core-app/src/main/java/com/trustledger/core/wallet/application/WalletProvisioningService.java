package com.trustledger.core.wallet.application;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.wallet.WalletCreated;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-W-1: a wallet follows the user's KYC level. Idempotent: applying the same level twice changes nothing.
 * <ul>
 *   <li>MIN, no wallet: open a MIN wallet and publish {@link WalletCreated}.</li>
 *   <li>FULL, MIN wallet: upgrade it to FULL.</li>
 *   <li>FULL, no wallet (the MIN event was lost): open a FULL wallet directly.</li>
 *   <li>Already at or above the level: nothing (never downgrades).</li>
 * </ul>
 */
@Service
public class WalletProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(WalletProvisioningService.class);

    private final WalletRepository wallets;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public WalletProvisioningService(WalletRepository wallets, ApplicationEventPublisher events, Clock clock) {
        this.wallets = wallets;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Its own transaction ({@code REQUIRES_NEW}), so that when two events race and this insert loses on the
     * unique {@code user_id}, the caller can simply call again: the retry finds the winner's wallet.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when a concurrent call created the
     *         wallet first
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(UUID userId, KycLevel level) {
        WalletTier target = level == KycLevel.FULL ? WalletTier.FULL : WalletTier.MIN;
        Instant now = clock.instant();
        Optional<Wallet> existing = wallets.lockByUserId(userId);
        if (existing.isPresent()) {
            Wallet wallet = existing.get();
            if (wallet.upgradeTo(target, now)) {
                wallets.saveAndFlush(wallet);
                log.info("Wallet {} upgraded to tier {}", wallet.getId(), target);
            }
            return;
        }
        Wallet wallet = wallets.saveAndFlush(Wallet.open(userId, target, now));
        // Published after the insert succeeded: a lost race publishes nothing.
        events.publishEvent(new WalletCreated(wallet.getId(), userId));
        log.info("Wallet {} opened with tier {} for user {}", wallet.getId(), target, userId);
    }
}
