package com.trustledger.core.wallet.domain;

import java.util.Collection;
import java.util.UUID;

/**
 * Port: does the current transaction hold the row lock of a wallet it loaded? Lets {@code WalletApi} refuse a
 * money movement on a wallet the caller forgot to lock.
 */
public interface WalletLocks {

    boolean isLockedForUpdate(Wallet wallet);

    /**
     * Forgets the wallets with these IDs that the current transaction loaded <em>without</em> a row lock (for
     * example a read-only lookup such as resolving a transfer recipient). Called right before locking them, so
     * the {@code SELECT ... FOR UPDATE} result replaces the stale snapshot instead of clashing with it. Wallets
     * already locked are kept. Never queries the database.
     */
    void evictUnlocked(Collection<UUID> walletIds);
}
