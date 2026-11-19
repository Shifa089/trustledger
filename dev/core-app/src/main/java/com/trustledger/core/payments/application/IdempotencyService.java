package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.IdempotencyKeyRecord;
import com.trustledger.core.payments.domain.IdempotencyKeyRepository;
import com.trustledger.core.payments.domain.IdempotencyStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency keys for money endpoints (FR-P-1, SPEC §4 "Transfer flow" steps 1 and 8).
 * <ol>
 * <li>{@link #claim}: a new key (or an expired one) becomes IN_PROGRESS and the request runs. A COMPLETED key
 * with the same request hash replays the stored response. A different hash is 422
 * {@code IDEMPOTENCY_KEY_REUSED}; a key still IN_PROGRESS is 409 {@code REQUEST_IN_PROGRESS}.</li>
 * <li>After the business transaction: {@link #complete} stores a 2xx response, {@link #release} deletes the key
 * of a request that failed (it moved no money, so a retry may run again).</li>
 * </ol>
 * Every method runs in its <em>own</em> short transaction ({@code REQUIRES_NEW}): the claim must be committed
 * and visible to a racing duplicate before the business work starts, and it must survive that work's rollback.
 */
@Service
public class IdempotencyService {

    /** A key released between our failed insert and our read is claimed again; more than this is a bug. */
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final IdempotencyKeyRepository keys;
    private final PaymentsProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository keys, PaymentsProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param requestHash SHA-256 of the canonical body ({@link RequestHasher})
     * @throws TrustLedgerException {@code IDEMPOTENCY_KEY_REUSED} (another body) or {@code REQUEST_IN_PROGRESS}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyClaim claim(UUID userId, UUID key, String requestHash) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestHash, "requestHash");
        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            if (keys.claim(userId, key, requestHash, now, now.plus(properties.idempotency().ttl()))) {
                return new IdempotencyClaim.Claimed();
            }
            Optional<IdempotencyKeyRecord> existing = keys.find(userId, key);
            if (existing.isPresent()) {
                return decide(existing.get(), requestHash);
            }
        }
        throw new TrustLedgerException(ErrorCode.REQUEST_IN_PROGRESS,
                "A request with this Idempotency-Key is being processed.");
    }

    /**
     * Stores the response of a completed request for replay. Returns {@code false} if the key was not
     * IN_PROGRESS (it expired and was cleaned up, or completed twice): nothing is stored then.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID userId, UUID key, int responseStatus, String responseBody) {
        if (responseStatus < 200 || responseStatus > 299) {
            throw new IllegalArgumentException("Only 2xx responses are stored: " + responseStatus);
        }
        return keys.complete(userId, key, responseStatus, responseBody);
    }

    /** Forgets the key of a failed request, so the client may retry with it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(UUID userId, UUID key) {
        return keys.release(userId, key);
    }

    /** One batch of the cleanup job: deletes up to {@code limit} expired keys. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredBatch(int limit) {
        return keys.deleteExpired(clock.instant(), limit);
    }

    /** Hash first: a different body is a client bug whatever state the original request is in. */
    private static IdempotencyClaim decide(IdempotencyKeyRecord existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new TrustLedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request body.");
        }
        if (existing.status() == IdempotencyStatus.IN_PROGRESS) {
            throw new TrustLedgerException(ErrorCode.REQUEST_IN_PROGRESS,
                    "A request with this Idempotency-Key is being processed.");
        }
        return new IdempotencyClaim.Replay(existing.responseStatus(), existing.responseBody());
    }
}
