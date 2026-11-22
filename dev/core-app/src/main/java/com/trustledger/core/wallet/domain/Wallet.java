package com.trustledger.core.wallet.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * A customer's wallet (FR-W-1 to FR-W-3): the aggregate that guards the cached balance and the freeze state.
 * <p>
 * Invariants, also enforced by database checks: {@code balance >= 0}, {@code held >= 0},
 * {@code held <= balance}. Money only moves through {@link #debit}, {@link #credit}, {@link #hold} and
 * {@link #releaseHold}; a FROZEN wallet can't send, receive or hold ({@link ErrorCode#WALLET_FROZEN}), but a
 * hold can still be released. A CLOSED wallet refuses everything.
 * <p>
 * The freeze history ({@link WalletFreeze}) belongs to the aggregate, so "at most one open freeze, and only
 * while FROZEN" is checked in one place.
 */
@Entity
@Table(name = "wallets", schema = "wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

    public static final int MAX_REASON_LENGTH = 500;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 8)
    private WalletTier tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WalletStatus status;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "held_minor", nullable = false)
    private long heldMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    /** The wallet's {@code WALLET:<id>} ledger account; set once, right after the wallet is created (M5). */
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "wallet", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @OrderBy("frozenAt ASC")
    private List<WalletFreeze> freezes = new ArrayList<>();

    /** A new, empty, ACTIVE INR wallet (FR-W-1). */
    public static Wallet open(UUID userId, WalletTier tier, Instant now) {
        Wallet wallet = new Wallet();
        wallet.userId = Objects.requireNonNull(userId, "userId");
        wallet.tier = Objects.requireNonNull(tier, "tier");
        wallet.status = WalletStatus.ACTIVE;
        wallet.currency = Money.INR;
        wallet.createdAt = Objects.requireNonNull(now, "now");
        wallet.updatedAt = now;
        return wallet;
    }

    public Money balance() {
        return Money.of(balanceMinor, currency);
    }

    public Money held() {
        return Money.of(heldMinor, currency);
    }

    /** FR-W-2: what the user can spend or transfer. */
    public Money available() {
        return Money.of(balanceMinor - heldMinor, currency);
    }

    /**
     * Moves the tier up to {@code target}. A wallet is never downgraded: a late MIN event after FULL changes
     * nothing.
     *
     * @return whether the tier changed
     */
    public boolean upgradeTo(WalletTier target, Instant now) {
        Objects.requireNonNull(target, "target");
        if (target.ordinal() <= tier.ordinal()) {
            return false;
        }
        this.tier = target;
        touch(now);
        return true;
    }

    /**
     * Records the wallet's ledger account. Set once: linking the same account again changes nothing, and a
     * different account is a bug (two ledgers for one wallet), so it fails loudly.
     *
     * @return whether the link was new
     */
    public boolean linkLedgerAccount(UUID accountId, Instant now) {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.equals(ledgerAccountId)) {
            return false;
        }
        if (ledgerAccountId != null) {
            throw new IllegalStateException("Wallet " + id + " is already linked to ledger account " + ledgerAccountId);
        }
        this.ledgerAccountId = accountId;
        touch(now);
        return true;
    }

    /** Takes money out of an ACTIVE wallet; the amount must not exceed the available balance. */
    public void debit(Money amount, Instant now) {
        requireMovable(amount, "debit");
        requireAvailable(amount);
        this.balanceMinor = balanceMinor - amount.amountMinor();
        touch(now);
    }

    /** Puts money into an ACTIVE wallet. The tier's maximum balance is checked by the caller (payments). */
    public void credit(Money amount, Instant now) {
        requireMovable(amount, "credit");
        this.balanceMinor = Math.addExact(balanceMinor, amount.amountMinor());
        touch(now);
    }

    /** Reserves part of the available balance (FR-P-3, transfer held for review). */
    public void hold(Money amount, Instant now) {
        requireMovable(amount, "hold");
        requireAvailable(amount);
        this.heldMinor = heldMinor + amount.amountMinor();
        touch(now);
    }

    /**
     * Gives a reservation back. Works on a FROZEN wallet too: rejecting a held transfer must return the money
     * even if the sender was frozen meanwhile. Releasing more than is held is a caller bug.
     */
    public void releaseHold(Money amount, Instant now) {
        requirePositive(amount);
        requireNotClosed("release a hold on");
        if (amount.amountMinor() > heldMinor) {
            throw new IllegalArgumentException("Cannot release more than is held");
        }
        this.heldMinor = heldMinor - amount.amountMinor();
        touch(now);
    }

    /**
     * ACTIVE -> FROZEN (FR-W-3), recording who, why and when in the freeze history.
     *
     * @param frozenBy the officer; may be {@code null} only for an {@link FreezeSource#AML} freeze
     */
    public WalletFreeze freeze(String reason, FreezeSource source, UUID frozenBy, Instant now) {
        Objects.requireNonNull(source, "source");
        if (source == FreezeSource.COMPLIANCE) {
            Objects.requireNonNull(frozenBy, "frozenBy");
        }
        String text = requireReason(reason);
        if (status != WalletStatus.ACTIVE) {
            throw invalid("freeze");
        }
        WalletFreeze freeze = WalletFreeze.start(this, source, text, frozenBy, now);
        freezes.add(freeze);
        this.status = WalletStatus.FROZEN;
        touch(now);
        return freeze;
    }

    /**
     * FROZEN -> ACTIVE (FR-W-3), closing the open freeze. An officer can't unfreeze their own wallet: the
     * same conflict of interest as reviewing their own KYC case.
     */
    public WalletFreeze unfreeze(UUID unfrozenBy, String reason, Instant now) {
        Objects.requireNonNull(unfrozenBy, "unfrozenBy");
        String text = requireReason(reason);
        if (status != WalletStatus.FROZEN) {
            throw invalid("unfreeze");
        }
        if (unfrozenBy.equals(userId)) {
            throw new TrustLedgerException(ErrorCode.MAKER_CHECKER_VIOLATION,
                    "An officer cannot unfreeze their own wallet.");
        }
        WalletFreeze open = currentFreeze()
                .orElseThrow(() -> new IllegalStateException("Wallet " + id + " is FROZEN without an open freeze"));
        open.end(unfrozenBy, text, now);
        this.status = WalletStatus.ACTIVE;
        touch(now);
        return open;
    }

    /**
     * ACTIVE -> CLOSED, for the owner's erasure (FR-PR-2). Only an empty wallet closes: money is never left behind on
     * a wallet nobody can use, so a balance (or a hold) is refused with {@link ErrorCode#WALLET_NOT_EMPTY}. A FROZEN
     * wallet is under a compliance hold and stays as it is ({@link ErrorCode#WALLET_FROZEN}). Closing a CLOSED wallet
     * is refused like any other step on it.
     */
    public void close(Instant now) {
        requireNotClosed("close");
        if (status == WalletStatus.FROZEN) {
            throw new TrustLedgerException(ErrorCode.WALLET_FROZEN,
                    "The wallet is frozen; it can't be closed until compliance has finished its review.");
        }
        if (balanceMinor != 0 || heldMinor != 0) {
            throw new TrustLedgerException(ErrorCode.WALLET_NOT_EMPTY,
                    "The wallet still holds " + balance().toDecimalString() + " INR. Transfer or spend it first.");
        }
        this.status = WalletStatus.CLOSED;
        touch(now);
    }

    /** The freeze in force, if the wallet is frozen. */
    public Optional<WalletFreeze> currentFreeze() {
        return freezes.stream().filter(WalletFreeze::isOpen).findFirst();
    }

    /** Every freeze, oldest first. Read-only. */
    public List<WalletFreeze> freezeHistory() {
        return Collections.unmodifiableList(freezes);
    }

    public boolean isOwnedBy(UUID candidateUserId) {
        return userId.equals(candidateUserId);
    }

    private void requireMovable(Money amount, String action) {
        requirePositive(amount);
        requireNotClosed(action);
        if (status == WalletStatus.FROZEN) {
            throw new TrustLedgerException(ErrorCode.WALLET_FROZEN, "The wallet is frozen.");
        }
    }

    private void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!currency.equals(amount.currency())) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "Amount must be in " + currency.getCurrencyCode() + ".");
        }
        if (amount.isZero()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Amount must be greater than zero.");
        }
    }

    private void requireNotClosed(String action) {
        if (status == WalletStatus.CLOSED) {
            throw invalid(action);
        }
    }

    private void requireAvailable(Money amount) {
        if (amount.amountMinor() > balanceMinor - heldMinor) {
            throw new TrustLedgerException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Available balance is lower than the amount.");
        }
    }

    private TrustLedgerException invalid(String action) {
        return new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                "Cannot " + action + " a wallet in status " + status + ".");
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > MAX_REASON_LENGTH) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "A reason of 1-" + MAX_REASON_LENGTH + " characters is required.");
        }
        return reason.strip();
    }
}
