package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link TransferRepository}. */
public interface JpaTransferRepository extends JpaRepository<Transfer, UUID>, TransferRepository {

    @Override
    @Query("""
            select coalesce(sum(t.amountMinor), 0) from Transfer t
            where t.senderWalletId = :senderWalletId and t.createdAt >= :fromInclusive and t.status in :statuses""")
    long sumOutgoingSince(@Param("senderWalletId") UUID senderWalletId, @Param("fromInclusive") Instant fromInclusive,
            @Param("statuses") Collection<TransferStatus> statuses);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") UUID id);
}
