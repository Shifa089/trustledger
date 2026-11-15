package com.trustledger.core.kyc;

import java.util.Objects;
import java.util.UUID;

/**
 * Published when a KYC case is approved (FR-KYC-4): MIN after automatic approval, FULL after the
 * checker's approval. The wallet module reacts to it (FR-W-1, M4). Carries IDs only, no personal data.
 */
public record KycLevelUpgraded(UUID userId, KycLevel level) {

    public KycLevelUpgraded {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(level, "level");
    }
}
