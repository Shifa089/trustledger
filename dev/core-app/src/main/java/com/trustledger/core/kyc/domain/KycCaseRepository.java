package com.trustledger.core.kyc.domain;

import com.trustledger.core.kyc.KycLevel;
import java.util.List;
import java.util.UUID;

/** Port for KYC cases; implemented by Spring Data in {@code kyc.infrastructure}. */
public interface KycCaseRepository {

    List<KycCase> findByUserIdOrderByCreatedAtAsc(UUID userId);

    boolean existsByUserIdAndLevelAndStatusNot(UUID userId, KycLevel level, KycStatus status);

    /** Flushes at once, so a unique-index violation (second live case) surfaces here and not at commit. */
    KycCase saveAndFlush(KycCase kycCase);
}
