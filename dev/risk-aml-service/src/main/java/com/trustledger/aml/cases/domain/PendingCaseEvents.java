package com.trustledger.aml.cases.domain;

import com.trustledger.events.AmlCaseStatusChangedV1;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The relay's view of the case-event outbox (ADR 0007): claim a batch of unsent events, then mark each one sent or
 * failed. Must run inside a transaction: the claim is a row lock held until commit.
 */
public interface PendingCaseEvents {

    /** Locks up to {@code limit} unsent events, oldest first, with {@code FOR UPDATE SKIP LOCKED}. */
    List<Pending> claim(int limit);

    void markPublished(UUID id, Instant publishedAt);

    void markFailed(UUID id);

    /** One claimed event: the contract to send and the correlation ID for its header. */
    record Pending(AmlCaseStatusChangedV1 event, String correlationId, int attempts) {
    }
}
