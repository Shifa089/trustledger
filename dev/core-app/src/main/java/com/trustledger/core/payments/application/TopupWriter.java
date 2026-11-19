package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short database transactions around the gateway call in {@link TopupService#create}. A separate bean, so each
 * method gets its own transaction through the Spring proxy (a call from inside {@code TopupService} itself would
 * bypass it). Every method commits before it returns; none of them makes a remote call.
 */
@Service
class TopupWriter {

    private final WalletApi wallets;
    private final TopupLimitChecker limits;
    private final TopupRepository topups;
    private final PaymentsProperties properties;
    private final Clock clock;

    TopupWriter(WalletApi wallets, TopupLimitChecker limits, TopupRepository topups, PaymentsProperties properties,
            Clock clock) {
        this.wallets = wallets;
        this.limits = limits;
        this.topups = topups;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Checks and saves a CREATED top-up under the wallet's row lock, then commits (and so releases the lock).
     *
     * @throws TrustLedgerException {@code KYC_REQUIRED} (no wallet), {@code VALIDATION_FAILED} (below the minimum),
     *         {@code WALLET_FROZEN}, {@code INVALID_STATE_TRANSITION} (closed wallet), {@code LIMIT_EXCEEDED}
     */
    @Transactional
    public Topup open(UUID userId, Money amount) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(amount, "amount");
        Money minimum = properties.topups().minAmount();
        if (amount.isLessThan(minimum)) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "The minimum top-up is " + minimum.toDecimalString() + ".");
        }
        UUID walletId = wallets.findByUser(userId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.KYC_REQUIRED,
                        "Complete KYC to open a wallet before adding money."))
                .walletId();
        Instant now = clock.instant();
        Topup topup = Topup.create(walletId, amount, now);

        // Serialises top-up creation per wallet, so the limit sums can't change before this commits.
        WalletView wallet = wallets.lockForUpdate(List.of(walletId)).getFirst();
        TransferService.requireActive(wallet, "Your wallet is frozen.");
        limits.check(wallet, wallets.limitsFor(walletId), amount);
        return topups.saveAndFlush(topup);
    }

    /** CREATED -> PENDING with the gateway's order ID. */
    @Transactional
    public Topup markPending(UUID topupId, String gatewayOrderId) {
        Topup topup = load(topupId);
        topup.markPending(gatewayOrderId, clock.instant());
        return topups.saveAndFlush(topup);
    }

    /** CREATED -> FAILED: the gateway order could not be created. */
    @Transactional
    public Topup markFailed(UUID topupId) {
        Topup topup = load(topupId);
        topup.fail(clock.instant());
        return topups.saveAndFlush(topup);
    }

    private Topup load(UUID topupId) {
        return topups.findById(topupId)
                .orElseThrow(() -> new IllegalStateException("Top-up " + topupId + " disappeared"));
    }
}
