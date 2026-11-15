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

    /** The limits of the wallet's current tier. */
    WalletLimits limitsFor(UUID walletId);
}
