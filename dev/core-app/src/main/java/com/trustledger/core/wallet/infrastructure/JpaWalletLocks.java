package com.trustledger.core.wallet.infrastructure;

import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletLocks;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

/**
 * Asks the persistence context which lock it took when it loaded the wallet. Only meaningful inside the
 * transaction that loaded it.
 */
@Component
class JpaWalletLocks implements WalletLocks {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean isLockedForUpdate(Wallet wallet) {
        return entityManager.contains(wallet)
                && entityManager.getLockMode(wallet) == LockModeType.PESSIMISTIC_WRITE;
    }

    /**
     * Why: a wallet read earlier in the same transaction (unlocked) stays in the persistence context. When the
     * locking query then returns a newer version of that row (another transaction committed in between),
     * Hibernate refuses to merge the two ({@code ObjectOptimisticLockingFailureException}). Without that check it
     * would silently hand back the stale snapshot, a lost update.
     * <p>
     * {@code getReference} returns the managed instance if there is one and otherwise an uninitialised proxy,
     * without a query. Discarding an unlocked snapshot loses nothing: wallets change only under their lock.
     */
    @Override
    public void evictUnlocked(Collection<UUID> walletIds) {
        for (UUID id : walletIds) {
            Wallet known = entityManager.getReference(Wallet.class, id);
            if (!Hibernate.isInitialized(known) || !isLockedForUpdate(known)) {
                entityManager.detach(known);
            }
        }
    }
}
