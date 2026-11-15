package com.trustledger.core.wallet.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for wallets; implemented by Spring Data in {@code wallet.infrastructure}. */
public interface WalletRepository {

    Optional<Wallet> findById(UUID id);

    Optional<Wallet> findByUserId(UUID userId);

    /**
     * {@code SELECT ... WHERE id IN (:ids) ORDER BY id FOR UPDATE}: the rows are locked in PostgreSQL's
     * ascending UUID order, the same order for every transaction, so lock waits can't form a cycle.
     */
    List<Wallet> lockAllOrderedById(Collection<UUID> ids);

    /** One wallet, {@code SELECT ... FOR UPDATE}. */
    Optional<Wallet> lockById(UUID id);

    /** The user's wallet, {@code SELECT ... FOR UPDATE}: a tier upgrade waits for a running transfer. */
    Optional<Wallet> lockByUserId(UUID userId);

    /** Flushes at once, so the unique {@code user_id} (a second wallet) surfaces here and not at commit. */
    Wallet saveAndFlush(Wallet wallet);
}
