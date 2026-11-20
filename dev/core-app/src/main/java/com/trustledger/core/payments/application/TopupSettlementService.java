package com.trustledger.core.payments.application;

import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles top-ups from what the gateway reports (FR-P-4, FR-P-6). The only place a top-up's money is booked: the
 * {@code payment.captured} / {@code payment.failed} webhook handlers and the {@link PendingTopupReconciler} all come
 * here, so a webhook and the reconciler racing on the same top-up are safe.
 * <p>
 * Every method joins the caller's transaction (the webhook processor's) or starts its own (the reconciler's), and
 * makes no remote call. Locks: the top-up row first ({@code FOR UPDATE}), then its wallet. Under the top-up lock the
 * status is read fresh, so the second of two racing settlements sees CAPTURED and does nothing.
 * <p>
 * Out-of-state results are ignored and logged, never thrown: a webhook that threw would be retried by the gateway
 * for a day without ever succeeding.
 */
@Service
public class TopupSettlementService {

    /** {@code referenceType} of top-up postings: with the top-up ID it makes a double posting impossible. */
    static final String REFERENCE_TYPE = "TOPUP";

    private static final Logger log = LoggerFactory.getLogger(TopupSettlementService.class);

    private final TopupRepository topups;
    private final WalletApi wallets;
    private final LedgerApi ledger;
    private final TopupLimitChecker limits;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TopupSettlementService(TopupRepository topups, WalletApi wallets, LedgerApi ledger, TopupLimitChecker limits,
            ApplicationEventPublisher events, Clock clock) {
        this.topups = topups;
        this.wallets = wallets;
        this.ledger = ledger;
        this.limits = limits;
        this.events = events;
        this.clock = clock;
    }

    /**
     * A captured payment for the order: credit the wallet (CAPTURED, {@link TopupCaptured} published) if it can take
     * the money now, else book it to suspense (PARKED, ADR 0006). A late capture of a FAILED or EXPIRED top-up is
     * settled the same way.
     *
     * @param payment a payment with status CAPTURED on {@code payment.orderId()}
     */
    @Transactional
    public SettlementOutcome capture(GatewayPayment payment) {
        Objects.requireNonNull(payment, "payment");
        if (payment.status() != GatewayPaymentStatus.CAPTURED || payment.orderId() == null) {
            throw new IllegalArgumentException("Not a captured payment on an order");
        }
        Optional<Topup> found = topups.findByGatewayOrderIdForUpdate(payment.orderId());
        if (found.isEmpty()) {
            log.warn("Captured payment {} for unknown order {} ignored", payment.paymentId(), payment.orderId());
            return SettlementOutcome.IGNORED;
        }
        Topup topup = found.get();
        if (topup.isSettled()) {
            if (payment.paymentId().equals(topup.getGatewayPaymentId())) {
                log.info("Top-up {} already settled by payment {}", topup.getId(), payment.paymentId());
            } else {
                log.error("Top-up {} already settled by payment {}; second captured payment {} needs manual review",
                        topup.getId(), topup.getGatewayPaymentId(), payment.paymentId());
            }
            return SettlementOutcome.IGNORED;
        }
        if (!topup.isSettleable()) {
            log.warn("Captured payment {} ignored: top-up {} is {}", payment.paymentId(), topup.getId(),
                    topup.getStatus());
            return SettlementOutcome.IGNORED;
        }
        Money amount = topup.amount();
        if (!payment.amount().equals(amount)) {
            log.error("Captured payment {} ignored: amount {} {} does not match top-up {}; needs manual review",
                    payment.paymentId(), payment.amount().toDecimalString(), payment.amount().currency(),
                    topup.getId());
            return SettlementOutcome.IGNORED;
        }

        UUID walletId = topup.getWalletId();
        WalletView wallet = wallets.lockForUpdate(List.of(walletId)).getFirst();
        Optional<ParkReason> refusal = limits.checkCapture(wallet, wallets.limitsFor(walletId), amount);
        Instant now = clock.instant();
        boolean late = topup.getStatus() != TopupStatus.PENDING;

        if (refusal.isPresent()) {
            UUID entryId = ledger.post(posting(topup, AccountCodes.SUSPENSE));
            topup.park(payment.paymentId(), entryId, now);
            topups.saveAndFlush(topup);
            log.warn("Top-up {} parked in suspense ({}), payment {}, journal entry {}{}", topup.getId(),
                    refusal.get(), payment.paymentId(), entryId, late ? " (late capture)" : "");
            return SettlementOutcome.PARKED;
        }

        UUID entryId = ledger.post(posting(topup, AccountCodes.wallet(walletId)));
        wallets.credit(walletId, amount);
        topup.capture(payment.paymentId(), entryId, now);
        topups.saveAndFlush(topup);
        events.publishEvent(new TopupCaptured(topup.getId(), walletId, amount, entryId, now));
        log.info("Top-up {} captured, payment {}, journal entry {}{}", topup.getId(), payment.paymentId(), entryId,
                late ? " (late capture)" : "");
        return SettlementOutcome.CAPTURED;
    }

    /** A failed payment attempt for the order: PENDING -> FAILED; any other status is left alone. */
    @Transactional
    public SettlementOutcome fail(GatewayPayment payment) {
        Objects.requireNonNull(payment, "payment");
        if (payment.orderId() == null) {
            throw new IllegalArgumentException("Not a payment on an order");
        }
        Optional<Topup> found = topups.findByGatewayOrderIdForUpdate(payment.orderId());
        if (found.isEmpty()) {
            log.warn("Failed payment {} for unknown order {} ignored", payment.paymentId(), payment.orderId());
            return SettlementOutcome.IGNORED;
        }
        Topup topup = found.get();
        if (topup.getStatus() != TopupStatus.PENDING) {
            log.info("Failed payment {} ignored: top-up {} is {}", payment.paymentId(), topup.getId(),
                    topup.getStatus());
            return SettlementOutcome.IGNORED;
        }
        topup.fail(clock.instant());
        topups.saveAndFlush(topup);
        log.info("Top-up {} failed, payment {} ({})", topup.getId(), payment.paymentId(), payment.errorCode());
        return SettlementOutcome.FAILED;
    }

    /** Nobody paid in time (FR-P-6): PENDING -> EXPIRED; any other status is left alone. */
    @Transactional
    public SettlementOutcome expire(UUID topupId) {
        Objects.requireNonNull(topupId, "topupId");
        Topup topup = topups.findByIdForUpdate(topupId)
                .orElseThrow(() -> new IllegalStateException("Top-up " + topupId + " disappeared"));
        if (topup.getStatus() != TopupStatus.PENDING) {
            return SettlementOutcome.IGNORED;
        }
        topup.expire(clock.instant());
        topups.saveAndFlush(topup);
        log.info("Top-up {} expired", topup.getId());
        return SettlementOutcome.EXPIRED;
    }

    /**
     * PENDING top-ups created before {@code createdBefore}, oldest first, as plain records: the reconciler reads
     * them in this short transaction and calls the gateway after it has ended.
     */
    @Transactional(readOnly = true)
    public List<DueTopup> duePending(Instant createdBefore, int limit) {
        return topups.findPendingCreatedBefore(createdBefore, limit).stream()
                .map(t -> new DueTopup(t.getId(), t.getGatewayOrderId(), t.getCreatedAt()))
                .toList();
    }

    /** A PENDING top-up for the reconciler. */
    public record DueTopup(UUID topupId, String gatewayOrderId, Instant createdAt) {
    }

    /**
     * SPEC §4 posting rule: the gateway holds the money for us (GATEWAY_CLEARING, asset, debited) and we owe it to
     * the wallet's owner, or, when parked, to someone yet to be settled (SUSPENSE, liability, credited).
     */
    static PostingCommand posting(Topup topup, String creditAccount) {
        Money amount = topup.amount();
        return PostingCommand.of(EntryType.TOPUP, REFERENCE_TYPE, topup.getId(), List.of(
                PostingLine.debit(AccountCodes.GATEWAY_CLEARING, amount),
                PostingLine.credit(creditAccount, amount)));
    }
}
