package com.trustledger.core.payments.application;

import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P2P transfers (FR-P-1 to FR-P-3), SPEC §4 "Transfer flow". Steps 1 and 8 (claim and store the idempotency
 * key) happen outside this class, in {@code IdempotencyInterceptor}; steps 2 to 7 are {@link #transfer}, in one
 * database transaction.
 * <p>
 * Any refusal throws {@link TrustLedgerException}; the transaction rolls back, so nothing is persisted and no
 * money moves. No remote calls are made while the wallet locks are held.
 */
@Service
public class TransferService {

    /** {@code referenceType} of transfer postings: with the transfer ID it makes a double posting impossible. */
    static final String REFERENCE_TYPE = "TRANSFER";

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletApi wallets;
    private final LedgerApi ledger;
    private final LimitChecker limits;
    private final RecipientResolver recipients;
    private final TransferRepository transfers;
    private final HeldTransferService held;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PaymentsMetrics metrics;

    public TransferService(WalletApi wallets, LedgerApi ledger, LimitChecker limits, RecipientResolver recipients,
            TransferRepository transfers, HeldTransferService held, ApplicationEventPublisher events, Clock clock,
            PaymentsMetrics metrics) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.limits = limits;
        this.recipients = recipients;
        this.transfers = transfers;
        this.held = held;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Counted in {@link PaymentsMetrics}: COMPLETED or HELD_FOR_REVIEW after the commit, FAILED for a refusal.
     *
     * @return the COMPLETED transfer, or HELD_FOR_REVIEW at or above the high-value threshold
     * @throws TrustLedgerException {@code KYC_REQUIRED} (no wallet, or a tier without P2P),
     *         {@code VALIDATION_FAILED} (own wallet, bad amount or note), {@code NOT_FOUND} (no such recipient),
     *         {@code WALLET_FROZEN}, {@code INVALID_STATE_TRANSITION} (closed wallet), {@code INSUFFICIENT_FUNDS},
     *         {@code LIMIT_EXCEEDED}
     */
    @Transactional
    public TransferResult transfer(TransferCommand command) {
        Objects.requireNonNull(command, "command");
        TransferResult result;
        try {
            result = execute(command);
        } catch (TrustLedgerException refused) {
            metrics.transferRefused();
            throw refused;
        }
        metrics.transferAccepted(result.status(), command.amount());
        return result;
    }

    /** Steps 2 to 7, inside the transaction of {@link #transfer}. */
    private TransferResult execute(TransferCommand command) {
        Instant now = clock.instant();

        // Step 2: validate. The sender wallet is the caller's own (no BOLA); the receiver is resolved without
        // a lock; Transfer.initiate checks amount, currency, self and note before anything is locked.
        UUID senderId = wallets.findByUser(command.senderUserId())
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.KYC_REQUIRED,
                        "Complete KYC to open a wallet before sending money."))
                .walletId();
        UUID receiverId = recipients.resolve(senderId, command.recipient()).walletId();
        Money amount = command.amount();
        Transfer transfer = Transfer.initiate(senderId, receiverId, amount, command.note(), now);

        // Step 3: lock both wallets. lockForUpdate sorts by ID (PostgreSQL order), so A->B and B->A take the
        // locks in the same order and can't deadlock. The views it returns are read under the locks.
        WalletView sender = null;
        WalletView receiver = null;
        for (WalletView locked : wallets.lockForUpdate(List.of(senderId, receiverId))) {
            if (locked.walletId().equals(senderId)) {
                sender = locked;
            } else {
                receiver = locked;
            }
        }
        Objects.requireNonNull(sender, "sender not locked");
        Objects.requireNonNull(receiver, "receiver not locked");

        // Step 4: checks that need the locked state. LimitChecker relies on the sender lock (see its javadoc).
        requireActive(sender, "Your wallet is frozen.");
        requireActive(receiver, "The recipient's wallet cannot receive money right now.");
        WalletLimits senderLimits = wallets.limitsFor(senderId);
        limits.checkSender(senderId, senderLimits, amount);
        if (sender.available().isLessThan(amount)) {
            throw new TrustLedgerException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient balance for this transfer.");
        }
        limits.checkReceiverCap(receiver, wallets.limitsFor(receiverId), amount);

        // Step 5: high value -> reserve the money and wait for a compliance officer. No posting, no event.
        if (amount.isGreaterThanOrEqualTo(senderLimits.highValueThreshold())) {
            return held.hold(transfer, now);
        }

        // Step 6: post. Saving first assigns the UUIDv7 ID, which the posting uses as its business reference.
        Transfer saved = transfers.save(transfer);
        UUID entryId = ledger.post(postingFor(saved));
        wallets.debit(senderId, amount);
        wallets.credit(receiverId, amount);
        saved.complete(entryId, now);

        // Step 7: publish. Spring Modulith records it in event_publication in this same transaction.
        events.publishEvent(new TransferCompleted(saved.getId(), senderId, receiverId, amount, entryId, now,
                EventMetadata.capture(clock)));
        log.info("Transfer {} completed, journal entry {}", saved.getId(), entryId);

        // Step 8 (commit, then store the response against the key) happens in the transaction proxy and in
        // IdempotencyInterceptor.afterCompletion.
        return TransferResult.of(saved);
    }

    /**
     * The journal entry of a transfer: both wallet accounts are liabilities, so the sender's is debited (the
     * platform owes them less) and the receiver's is credited (the platform owes them more).
     */
    static PostingCommand postingFor(Transfer transfer) {
        Money amount = transfer.amount();
        return PostingCommand.of(EntryType.P2P_TRANSFER, REFERENCE_TYPE, transfer.getId(), List.of(
                PostingLine.debit(AccountCodes.wallet(transfer.getSenderWalletId()), amount),
                PostingLine.credit(AccountCodes.wallet(transfer.getReceiverWalletId()), amount)));
    }

    /**
     * FROZEN -> WALLET_FROZEN; CLOSED -> INVALID_STATE_TRANSITION, the same codes WalletApi itself uses. Also used
     * by {@link HeldTransferService#approve}.
     */
    static void requireActive(WalletView wallet, String frozenMessage) {
        switch (wallet.status()) {
            case ACTIVE -> { }
            case FROZEN -> throw new TrustLedgerException(ErrorCode.WALLET_FROZEN, frozenMessage);
            case CLOSED -> throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The wallet is closed.");
        }
    }
}
