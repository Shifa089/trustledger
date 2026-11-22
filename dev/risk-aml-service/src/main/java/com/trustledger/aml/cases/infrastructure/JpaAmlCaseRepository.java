package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.AmlCase;
import com.trustledger.aml.cases.domain.AmlCaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Spring Data implementation of {@link AmlCaseRepository}; the bare {@link Repository}, so no delete. */
public interface JpaAmlCaseRepository extends Repository<AmlCase, UUID>, AmlCaseRepository {

    @Override
    @Query("""
            SELECT c FROM AmlCase c
            WHERE c.walletId = :walletId
              AND c.status IN (com.trustledger.aml.cases.domain.CaseStatus.OPEN,
                               com.trustledger.aml.cases.domain.CaseStatus.INVESTIGATING)
            """)
    Optional<AmlCase> findActiveByWalletId(@Param("walletId") UUID walletId);
}
