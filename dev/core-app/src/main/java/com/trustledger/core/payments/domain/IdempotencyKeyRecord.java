package com.trustledger.core.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A row of {@code payments.idempotency_keys}.
 *
 * @param responseStatus the stored HTTP status; {@code null} while IN_PROGRESS
 * @param responseBody   the stored JSON body; {@code null} while IN_PROGRESS or for an empty 2xx body
 */
public record IdempotencyKeyRecord(UUID userId, UUID key, String requestHash, IdempotencyStatus status,
        Integer responseStatus, String responseBody, Instant expiresAt) {

    public IdempotencyKeyRecord {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
