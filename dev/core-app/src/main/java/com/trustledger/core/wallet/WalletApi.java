package com.trustledger.core.wallet;

import com.trustledger.core.shared.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The wallet module's public API (Spring Modulith: only this base package is visible to other modules).
 * Payments (M6, M7) moves money only through it.
 * <p>
 * <b>Money movement contract.</b> Inside one transaction the caller first calls {@link #lockForUpdate} with
 * every wallet it will touch, then {@link #debit}, {@link #credit}, {@link #hold} or {@link #releaseHold}.
 * These require an active transaction ({@code MANDATORY}) and refuse, with {@link IllegalStateException}, a
 * wallet that was not locked first: a missing lock is a bug, not a user error. No remote call may happen
 * while the locks are held.
 * <p>
 * Business refusals throw {@link com.trustledger.core.shared.TrustLedgerException}: {@code WALLET_FROZEN},
 * {@code INSUFFICIENT_FUNDS}, {@code INVALID_STATE_TRANSITION} (closed wallet), {@code VALIDATION_FAILED}
 * (zero amount or another currency), {@code NOT_FOUND}. The maximum balance is <em>not</em> checked by
 * {@link #credit}: payments checks it with {@link #limitsFor}, because a reversal must always be able to pay
 * money back.
 */
public interface WalletApi {

    /** The user's wallet, if they have reached minimum KYC. */
    Optional<WalletView> findByUser(UUID userId);

    /** A wallet by ID, without locking it (e.g. resolving a transfer recipient before the locks are taken). */
    Optional<WalletView> findById(UUID walletId);

    /**
     * Locks the wallets with {@code SELECT ... FOR UPDATE} (PESSIMISTIC_WRITE) in ascending ID order, so two
     * transfers in opposite directions can't deadlock. The order is PostgreSQL's {@code ORDER BY id}, which
     * is not the same as Java's {@code UUID.compareTo}. Duplicate IDs are ignored.
     *
     * @return the locked wallets, in the order they were locked
     * @throws com.trustledger.core.shared.TrustLedgerException {@code NOT_FOUND} if any ID is unknown
     */
    List<WalletView> lockForUpdate(List<UUID> walletIds);

    /** Takes money out: needs an active wallet and enough available balance. */
    WalletView debit(UUID walletId, Money amount);

    /** Puts money in: needs an active wallet. */
    WalletView credit(UUID walletId, Money amount);

    /** Reserves money for a transfer held for review (FR-P-3): needs an active wallet and enough available. */
    WalletView hold(UUID walletId, Money amount);

    /** Gives a reservation back. Allowed on a frozen wallet, so a rejected held transfer can be undone. */
    WalletView releaseHold(UUID walletId, Money amount);

    /**
     * Closes an empty wallet for its owner's erasure (FR-PR-2). Same contract as money movement: needs an active
     * transaction ({@code MANDATORY}) and the wallet locked with {@link #lockForUpdate} first, so no transfer can put
     * money on it between the check and the close.
     *
     * @throws com.trustledger.core.shared.TrustLedgerException {@code WALLET_NOT_EMPTY} with a balance or hold,
     *         {@code WALLET_FROZEN} for a frozen wallet, {@code INVALID_STATE_TRANSITION} if already closed
     */
    WalletView close(UUID walletId);

    /** The limits of the wallet's current tier. */
    WalletLimits limitsFor(UUID walletId);

    /**
     * FR-AML-3: the AML service asks to freeze a wallet. Runs in its own transaction and is idempotent by
     * {@code eventId} ({@code wallet.processed_events}): a redelivered request returns {@link AmlFreezeOutcome#DUPLICATE}
     * and changes nothing. An ACTIVE wallet is frozen with source AML and the reason
     * {@code "AML case <caseId>, rule <ruleCode>"}; {@code WalletFrozen} is published as for an officer's freeze.
     *
     * @throws com.trustledger.core.shared.TrustLedgerException {@code NOT_FOUND} for an unknown wallet (the event is
     *                                                          then not marked processed)
     */
    AmlFreezeOutcome freezeForAml(UUID eventId, UUID walletId, UUID caseId, String ruleCode);
}
