package com.trustledger.core.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/** Port for {@code wallet.processed_events}: the inbox that makes the Kafka consumers idempotent by event ID. */
public interface ProcessedEventRepository {

    /**
     * Claims the event for this transaction.
     *
     * @return {@code true} the first time; {@code false} if the event was already processed (a redelivery)
     */
    boolean markProcessed(UUID eventId, String topic, Instant processedAt);
}
