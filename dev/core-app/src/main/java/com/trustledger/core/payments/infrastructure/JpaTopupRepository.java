package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link TopupRepository}. */
public interface JpaTopupRepository extends JpaRepository<Topup, UUID>, TopupRepository {

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
