package com.trustledger.core.wallet.infrastructure;

import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletLocks;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
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
}
