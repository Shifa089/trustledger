package com.trustledger.core.kyc.domain;

import com.trustledger.core.kyc.KycLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Port for KYC cases; implemented by Spring Data in {@code kyc.infrastructure}. */
public interface KycCaseRepository {

    Optional<KycCase> findById(UUID id);

    /**
     * Loads the case with {@code SELECT ... FOR UPDATE}: concurrent document uploads and officer decisions on
     * the same case run one after the other. Never call a remote service while holding it.
     */
    Optional<KycCase> lockById(UUID id);

    List<KycCase> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** The compliance queue; the caller supplies the sort (oldest first). */
    Page<KycCase> findByLevelAndStatus(KycLevel level, KycStatus status, Pageable pageable);

    boolean existsByUserIdAndLevelAndStatus(UUID userId, KycLevel level, KycStatus status);

    boolean existsByUserIdAndLevelAndStatusNot(UUID userId, KycLevel level, KycStatus status);

    /** Flushes at once, so a unique-index violation (second live case) surfaces here and not at commit. */
    KycCase saveAndFlush(KycCase kycCase);
}
