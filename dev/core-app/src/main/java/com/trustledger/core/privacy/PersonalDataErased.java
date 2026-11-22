package com.trustledger.core.privacy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's personal data was erased at their request (FR-PR-2). Published in the erasure's transaction; the audit
 * module turns it into an audit event before the commit (FR-A-1), so the erasure and its evidence commit together.
 * Carries IDs only: there is nothing personal left to carry.
 *
 * @param walletId the wallet closed by the erasure, or {@code null} if the user never had one
 */
public record PersonalDataErased(UUID userId, UUID dataRequestId, UUID walletId, Instant erasedAt) {

    public PersonalDataErased {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(dataRequestId, "dataRequestId");
        Objects.requireNonNull(erasedAt, "erasedAt");
    }
}
