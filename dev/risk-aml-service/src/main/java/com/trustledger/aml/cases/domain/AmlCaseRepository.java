package com.trustledger.aml.cases.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Port for {@code aml.cases}. Lists are oldest first: the queue an officer works through (FR-AML-4). */
public interface AmlCaseRepository {

    AmlCase save(AmlCase amlCase);

    Optional<AmlCase> findById(UUID id);

    /** The wallet's OPEN or INVESTIGATING case; there is at most one. */
    Optional<AmlCase> findActiveByWalletId(UUID walletId);

    Page<AmlCase> findAllByOrderByCreatedAtAscIdAsc(Pageable pageable);

    Page<AmlCase> findByStatusOrderByCreatedAtAscIdAsc(CaseStatus status, Pageable pageable);

    Page<AmlCase> findByWalletIdOrderByCreatedAtAscIdAsc(UUID walletId, Pageable pageable);

    Page<AmlCase> findByStatusAndWalletIdOrderByCreatedAtAscIdAsc(CaseStatus status, UUID walletId,
            Pageable pageable);
}
