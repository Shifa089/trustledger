package com.trustledger.core.payments.application;

import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * High-value transfers (FR-P-3): putting one on hold, and the compliance officer's decision on it. Creating a
 * hold and releasing it live in one class so the two sides stay symmetric.
 * <ul>
 *   <li>{@link #hold}: step 5 of the transfer flow, called by {@link TransferService#transfer} inside its
 *       transaction and under its wallet locks.</li>
 *   <li>{@link #approve}: post the journal entry, release the hold, move both balances, publish
 *       {@link TransferCompleted}, all in one transaction.</li>
 *   <li>{@link #reject}: release the hold. Nothing is posted and nothing is published.</li>
 * </ul>
 * <b>Lock order</b> for a decision: the transfer row first ({@code FOR UPDATE}), then the wallets in ascending ID
 * order through {@link WalletApi#lockForUpdate}. Two officers deciding the same transfer queue on the transfer
 * row, and the second one sees the first one's result. {@code transfer()} never locks an existing transfer row,
 * so this order can't form a cycle with it (ADR 0004).
 * <p>
 * Who may decide is enforced at the web layer (COMPLIANCE_OFFICER only); the officer's user ID is recorded as
 * {@code reviewed_by}.
 */
@Service
public class HeldTransferService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(HeldTransferService.class);

    private final WalletApi wallets;
    private final LedgerApi ledger;
    private final LimitChecker limits;
    private final TransferRepository transfers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public HeldTransferService(WalletApi wallets, LedgerApi ledger, LimitChecker limits,
            TransferRepository transfers, ApplicationEventPublisher events, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.limits = limits;
        this.transfers = transfers;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Step 5 of the transfer flow: reserve the amount on the sender's wallet and park the transfer as
     * HELD_FOR_REVIEW. No posting, no event. Runs only inside the caller's transaction, after the caller has
     * locked the sender and done every check ({@code MANDATORY}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    TransferResult hold(Transfer transfer, Instant now) {
        Objects.requireNonNull(transfer, "transfer");
        wallets.hold(transfer.getSenderWalletId(), transfer.amount());
        transfer.holdForReview(now);
        Transfer held = transfers.save(transfer);
        log.info("Transfer {} held for review", held.getId());
        return TransferResult.of(held);
    }

    /**
     * Approves a held transfer: the hold is released and the money moves exactly as an ordinary transfer would.
     * Both wallets must still be active, and the receiver's balance cap is checked again, because money may have
     * arrived while the transfer waited.
     *
     * @throws TrustLedgerException {@code NOT_FOUND}, {@code INVALID_STATE_TRANSITION} (not held, e.g. already
     *         decided), {@code WALLET_FROZEN}, {@code LIMIT_EXCEEDED} (receiver cap)
     */
    @Transactional
    public HeldTransferView approve(UUID transferId, UUID officerId) {
        Objects.requireNonNull(officerId, "officerId");
        Instant now = clock.instant();
        Transfer transfer = lockHeld(transferId, "approve");
        UUID senderId = transfer.getSenderWalletId();
        UUID receiverId = transfer.getReceiverWalletId();
        Money amount = transfer.amount();

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
        TransferService.requireActive(sender, "The sender's wallet is frozen. Reject the transfer or unfreeze "
                + "the wallet first.");
        TransferService.requireActive(receiver, "The recipient's wallet is frozen.");
        limits.checkReceiverCap(receiver, wallets.limitsFor(receiverId), amount);

        // Post before touching any wallet, as transfer() does. The posting's queries trigger a Hibernate
        // auto-flush; a wallet changed before it would be flushed, its lock mode would read back as WRITE rather
        // than PESSIMISTIC_WRITE, and WalletApi would refuse the next change as "not locked".
        UUID entryId = ledger.post(TransferService.postingFor(transfer));
        wallets.releaseHold(senderId, amount);
        wallets.debit(senderId, amount);
        wallets.credit(receiverId, amount);
        transfer.approve(officerId, entryId, now);

        events.publishEvent(new TransferCompleted(transfer.getId(), senderId, receiverId, amount, entryId, now));
        log.info("Transfer {} approved, journal entry {}", transfer.getId(), entryId);
        return HeldTransferView.of(transfer);
    }

    /**
     * Rejects a held transfer and gives the reserved amount back. Works on a frozen sender wallet too, so a
     * suspicious transfer can be undone while the wallet stays frozen.
     *
     * @throws TrustLedgerException {@code NOT_FOUND}, {@code INVALID_STATE_TRANSITION} (not held)
     */
    @Transactional
    public HeldTransferView reject(UUID transferId, UUID officerId) {
        Objects.requireNonNull(officerId, "officerId");
        Instant now = clock.instant();
        Transfer transfer = lockHeld(transferId, "reject");
        wallets.lockForUpdate(List.of(transfer.getSenderWalletId()));
        wallets.releaseHold(transfer.getSenderWalletId(), transfer.amount());
        transfer.reject(officerId, now);
        log.info("Transfer {} rejected, hold released", transfer.getId());
        return HeldTransferView.of(transfer);
    }

    /** {@code GET /compliance/transfers}: transfers in one status, oldest first (first in, first reviewed). */
    @Transactional(readOnly = true)
    public HeldTransferPage queue(TransferStatus status, int page, int size) {
        Objects.requireNonNull(status, "status");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "page must be >= 0 and size 1-" + MAX_PAGE_SIZE + ".");
        }
        Page<Transfer> result = transfers.findByStatus(status,
                PageRequest.of(page, size, Sort.by("createdAt").ascending().and(Sort.by("id"))));
        return new HeldTransferPage(result.getContent().stream().map(HeldTransferView::of).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Locks the transfer row and requires HELD_FOR_REVIEW before any wallet is touched. The aggregate checks the
     * state again in {@code approve}/{@code reject}; checking here first keeps a late second decision from
     * locking wallets or releasing a hold it doesn't own.
     */
    private Transfer lockHeld(UUID transferId, String action) {
        Objects.requireNonNull(transferId, "transferId");
        Transfer transfer = transfers.findByIdForUpdate(transferId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Transfer not found."));
        if (transfer.getStatus() != TransferStatus.HELD_FOR_REVIEW) {
            throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + action + " a transfer in status " + transfer.getStatus() + ".");
        }
        return transfer;
    }
}
