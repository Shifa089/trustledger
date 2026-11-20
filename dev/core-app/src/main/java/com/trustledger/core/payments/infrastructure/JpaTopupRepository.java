package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link TopupRepository}. */
public interface JpaTopupRepository extends JpaRepository<Topup, UUID>, TopupRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.gatewayOrderId = :orderId")
    Optional<Topup> findByGatewayOrderIdForUpdate(@Param("orderId") String gatewayOrderId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.id = :id")
    Optional<Topup> findByIdForUpdate(@Param("id") UUID id);

    @Override
    default List<Topup> findPendingCreatedBefore(Instant createdBefore, int limit) {
        return findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(TopupStatus.PENDING, createdBefore,
                Limit.of(limit));
    }

    /** Uses the partial index {@code ix_topups_pending_created} (V15). */
    List<Topup> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(TopupStatus status, Instant createdBefore,
            Limit limit);

    @Override
    @Query("""
            select coalesce(sum(t.amountMinor), 0) from Topup t
            where t.walletId = :walletId and t.createdAt >= :fromInclusive and t.status in :statuses""")
    long sumCreatedSince(@Param("walletId") UUID walletId, @Param("fromInclusive") Instant fromInclusive,
            @Param("statuses") Collection<TopupStatus> statuses);

    @Override
    @Query("""
            select coalesce(sum(t.amountMinor), 0) from Topup t
            where t.walletId = :walletId and t.status in :statuses""")
    long sumByStatus(@Param("walletId") UUID walletId, @Param("statuses") Collection<TopupStatus> statuses);
}
