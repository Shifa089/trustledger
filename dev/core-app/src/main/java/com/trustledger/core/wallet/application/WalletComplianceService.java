package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.AmlFreezeOutcome;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletUnfrozen;
import com.trustledger.core.wallet.domain.ProcessedEventRepository;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import com.trustledger.events.AccountFreezeRequestedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-W-3: compliance officers freeze and unfreeze wallets, with a reason. The wallet row is locked first, so
 * a freeze waits for a running transfer and the next transfer sees FROZEN. The officer comes from the token.
 * <p>
 * FR-AML-3: the AML service's freeze requests take the same path ({@link #freezeForAml}), with source AML and no
 * officer. Unfreezing an AML freeze is always an officer's decision.
 */
@Service
public class WalletComplianceService {

    private static final Logger log = LoggerFactory.getLogger(WalletComplianceService.class);

    /** The rule codes the AML service uses (R1..R4 today); anything else in a request is a producer bug. */
    private static final Pattern RULE_CODE = Pattern.compile("R[0-9]{1,2}");

    private final WalletRepository wallets;
    private final ProcessedEventRepository processedEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public WalletComplianceService(WalletRepository wallets, ProcessedEventRepository processedEvents,
            ApplicationEventPublisher events, Clock clock) {
        this.wallets = wallets;
        this.processedEvents = processedEvents;
        this.events = events;
        this.clock = clock;
    }

    /**
     * An officer's read of any wallet: status, balances and the open freeze (source COMPLIANCE or AML). No lock: the
     * console only shows it, and freeze/unfreeze lock the row themselves.
     */
    @Transactional(readOnly = true)
    public ComplianceWalletView view(UUID walletId) {
        return wallets.findById(walletId).map(ComplianceWalletView::of)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Wallet not found."));
    }

    @Transactional
    public ComplianceWalletView freeze(UUID walletId, UUID officerId, String reason) {
        Wallet wallet = lock(walletId);
        Instant now = clock.instant();
        wallet.freeze(reason, FreezeSource.COMPLIANCE, officerId, now);
        // Flush before publishing: if the database refuses (e.g. a second open freeze), no event goes out.
        wallets.saveAndFlush(wallet);
        events.publishEvent(new WalletFrozen(wallet.getId(), wallet.getUserId(), FreezeSource.COMPLIANCE, now,
                EventMetadata.capture(clock)));
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

    /**
     * FR-AML-3: a freeze requested by the AML service (see {@link com.trustledger.core.wallet.WalletApi#freezeForAml}).
     * One transaction: claim the event ID, lock the wallet, freeze it, publish {@link WalletFrozen}. A failure rolls the
     * claim back too, so the Kafka retry starts clean.
     */
    @Transactional
    public AmlFreezeOutcome freezeForAml(UUID eventId, UUID walletId, UUID caseId, String ruleCode) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(caseId, "caseId");
        if (ruleCode == null || !RULE_CODE.matcher(ruleCode).matches()) {
            throw new IllegalArgumentException("ruleCode must look like R1..R99");
        }
        Instant now = clock.instant();
        if (!processedEvents.markProcessed(eventId, AccountFreezeRequestedV1.TOPIC, now)) {
            log.info("Skipped already processed AML freeze request eventId={} wallet={}", eventId, walletId);
            return AmlFreezeOutcome.DUPLICATE;
        }
        Wallet wallet = lock(walletId);
        if (wallet.getStatus() == WalletStatus.FROZEN) {
            log.info("AML freeze request for an already frozen wallet {}; case={} eventId={}", walletId, caseId,
                    eventId);
            return AmlFreezeOutcome.ALREADY_FROZEN;
        }
        if (wallet.getStatus() == WalletStatus.CLOSED) {
            log.warn("AML freeze request for a closed wallet {}; case={} eventId={}", walletId, caseId, eventId);
            return AmlFreezeOutcome.WALLET_CLOSED;
        }
        wallet.freeze("AML case " + caseId + ", rule " + ruleCode, FreezeSource.AML, null, now);
        wallets.saveAndFlush(wallet);
        events.publishEvent(new WalletFrozen(wallet.getId(), wallet.getUserId(), FreezeSource.AML, now,
                EventMetadata.capture(clock)));
        log.info("Wallet {} frozen by AML case={} rule={} eventId={}", walletId, caseId, ruleCode, eventId);
        return AmlFreezeOutcome.FROZEN;
    }

    private Wallet lock(UUID walletId) {
        return wallets.lockById(walletId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Wallet not found."));
    }
}
