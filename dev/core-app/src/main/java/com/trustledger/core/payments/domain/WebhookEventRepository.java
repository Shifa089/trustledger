package com.trustledger.core.payments.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port for {@code payments.webhook_events}. Every method joins the caller's transaction. */
public interface WebhookEventRepository {

    /**
     * Inserts an accepted event unless an accepted event with the same gateway event ID exists. Safe when two
     * deliveries of the same event race: the second waits for the first to commit, then inserts nothing.
     *
     * @return true if this call inserted the row
     */
    boolean insertIfAbsent(WebhookEvent accepted);

    /** Inserts a rejected delivery. Rejected rows never conflict with anything. */
    void insertRejected(WebhookEvent rejected);

    /** The accepted event with this gateway ID, locked ({@code FOR UPDATE}) until the transaction ends. */
    Optional<WebhookEvent> findAcceptedForUpdate(String gatewayEventId);

    /** Sets {@code processed_at} on an accepted, unprocessed row; returns false if no row matched. */
    boolean markProcessed(UUID id, Instant processedAt);
}
