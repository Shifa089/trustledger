package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletView;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletLocks;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WalletApi} for payments. Money movement joins the caller's transaction ({@code MANDATORY}): the
 * caller owns the transaction boundary, so the wallet change, the ledger posting and the transfer row commit
 * or roll back together (one DB transaction per money movement).
 */
@Service
class WalletApiImpl implements WalletApi {

    private final WalletRepository wallets;
    private final WalletLocks locks;
    private final LimitsProperties limits;
    private final Clock clock;

    WalletApiImpl(WalletRepository wallets, WalletLocks locks, LimitsProperties limits, Clock clock) {
        this.wallets = wallets;
        this.locks = locks;
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WalletView> findByUser(UUID userId) {
        return wallets.findByUserId(userId).map(WalletViews::of);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WalletView> findById(UUID walletId) {
        Objects.requireNonNull(walletId, "walletId");
        return wallets.findById(walletId).map(WalletViews::of);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<WalletView> lockForUpdate(List<UUID> walletIds) {
        Objects.requireNonNull(walletIds, "walletIds");
        // Not contains(null): immutable lists (List.of) throw on that query.
        if (walletIds.isEmpty() || walletIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("walletIds must be non-empty and contain no null");
        }
        Set<UUID> distinct = new LinkedHashSet<>(walletIds);
        // A caller may have read these wallets unlocked earlier in this transaction (findById, findByUser).
        locks.evictUnlocked(distinct);
        List<Wallet> locked = wallets.lockAllOrderedById(distinct);
        if (locked.size() != distinct.size()) {
            throw notFound();
        }
        return locked.stream().map(WalletViews::of).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletView debit(UUID walletId, Money amount) {
        return change(walletId, amount, (wallet, money) -> wallet.debit(money, clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletView credit(UUID walletId, Money amount) {
        return change(walletId, amount, (wallet, money) -> wallet.credit(money, clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletView hold(UUID walletId, Money amount) {
        return change(walletId, amount, (wallet, money) -> wallet.hold(money, clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletView releaseHold(UUID walletId, Money amount) {
        return change(walletId, amount, (wallet, money) -> wallet.releaseHold(money, clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public WalletLimits limitsFor(UUID walletId) {
        Wallet wallet = wallets.findById(walletId).orElseThrow(WalletApiImpl::notFound);
        return limits.forTier(wallet.getTier());
    }

    /**
     * The wallet comes from the persistence context, where {@link #lockForUpdate} put it; no extra query.
     * The change is written at commit by dirty checking (and the database checks run then too).
     */
    private WalletView change(UUID walletId, Money amount, BiConsumer<Wallet, Money> operation) {
        Objects.requireNonNull(walletId, "walletId");
        Wallet wallet = wallets.findById(walletId).orElseThrow(WalletApiImpl::notFound);
        if (!locks.isLockedForUpdate(wallet)) {
            throw new IllegalStateException(
                    "Wallet " + walletId + " must be locked with lockForUpdate in this transaction first");
        }
        operation.accept(wallet, amount);
        return WalletViews.of(wallet);
    }

    private static TrustLedgerException notFound() {
        return new TrustLedgerException(ErrorCode.NOT_FOUND, "Wallet not found.");
    }
}
