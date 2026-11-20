package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.WebhookEvent;

/**
 * Processes one type of accepted gateway webhook event (for example {@code payment.captured}). Called by
 * {@link WebhookProcessor} inside the processing transaction, at most once per event: if it throws, nothing it did
 * is committed, the event stays unprocessed, and the gateway's retry runs it again. At most one handler per type.
 */
public interface WebhookEventHandler {

    /** The event type this handler processes, e.g. {@code payment.captured}. */
    String eventType();

    void handle(WebhookEvent event);
}
