package com.trustledger.core.wallet.infrastructure;

import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link WalletRepository}. */
public interface JpaWalletRepository extends JpaRepository<Wallet, UUID>, WalletRepository {

    /** The {@code ORDER BY} is what fixes the lock order: PostgreSQL locks the rows as it returns them. */
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id in :ids order by w.id")
    List<Wallet> lockAllOrderedById(@Param("ids") Collection<UUID> ids);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> lockById(@Param("id") UUID id);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> lockByUserId(@Param("userId") UUID userId);
}
