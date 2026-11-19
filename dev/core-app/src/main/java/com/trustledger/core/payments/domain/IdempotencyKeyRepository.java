package com.trustledger.core.payments.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for {@code payments.idempotency_keys}; implemented with plain SQL in {@code payments.infrastructure},
 * because the claim needs {@code INSERT ... ON CONFLICT}, which JPA can't express.
 */
public interface IdempotencyKeyRepository {

    /**
     * Inserts an IN_PROGRESS row, or takes over an <em>expired</em> row with the same key. The primary key
     * {@code (user_id, idem_key)} decides races: of two concurrent claims exactly one gets {@code true}.
     *
     * @return {@code true} if this call now owns the key
     */
    boolean claim(UUID userId, UUID key, String requestHash, Instant now, Instant expiresAt);

    Optional<IdempotencyKeyRecord> find(UUID userId, UUID key);

    /** IN_PROGRESS -> COMPLETED with the response. {@code false} if the row is not IN_PROGRESS any more. */
    boolean complete(UUID userId, UUID key, int responseStatus, String responseBody);

    /** Deletes an IN_PROGRESS row (the request failed and moved no money). */
    boolean release(UUID userId, UUID key);

    /** Deletes up to {@code limit} rows that expired at or before {@code now}. */
    int deleteExpired(Instant now, int limit);
}
