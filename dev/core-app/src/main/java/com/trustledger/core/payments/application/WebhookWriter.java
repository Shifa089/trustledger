package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.WebhookEvent;
import com.trustledger.core.payments.domain.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores webhook deliveries, each in its own short transaction that commits before processing starts. A separate
 * bean from {@link WebhookService}, so the calls go through the Spring proxy.
 */
@Service
class WebhookWriter {

    private final WebhookEventRepository events;

    WebhookWriter(WebhookEventRepository events) {
        this.events = events;
    }

    /** @return false if an accepted event with this gateway ID was already stored (a redelivery) */
    @Transactional
    public boolean storeAccepted(WebhookEvent accepted) {
        return events.insertIfAbsent(accepted);
    }

    @Transactional
    public void storeRejected(WebhookEvent rejected) {
        events.insertRejected(rejected);
    }
}
