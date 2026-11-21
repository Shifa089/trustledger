package com.trustledger.aml.facts.domain;

import java.time.Instant;
import java.util.UUID;

/** Port for {@code aml.processed_events}, the idempotent-consumer table. */
public interface ProcessedEventRepository {

    /**
     * Claims the event for the current transaction.
     *
     * @return {@code true} if this call inserted the row (first delivery), {@code false} if the event was already
     *         processed. When another transaction is inserting the same ID right now, this waits for it and then
     *         returns {@code false} if it committed, so two concurrent deliveries can't both win.
     */
    boolean markProcessed(UUID eventId, String topic, Instant processedAt);
}
