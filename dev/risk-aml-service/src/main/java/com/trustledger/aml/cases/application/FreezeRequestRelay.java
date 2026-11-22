package com.trustledger.aml.cases.application;

import com.trustledger.aml.cases.domain.FreezeRequestPublisher;
import com.trustledger.aml.cases.domain.PendingFreezeRequests;
import com.trustledger.aml.cases.domain.PendingFreezeRequests.Pending;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox relay for freeze requests (FR-AML-3, ADR 0007). Each run claims a batch of unsent requests
 * ({@code FOR UPDATE SKIP LOCKED}, so two instances never send the same row at the same time), sends each one and
 * waits for the broker, then marks it published.
 * <ul>
 * <li>A failed send marks one more attempt and ends the run: the broker is probably down, and the next requests
 * would only wait for the same timeout. They all stay pending for the next run.</li>
 * <li>A crash after the send but before the commit leaves the row pending: it is sent again with the <b>same</b>
 * {@code eventId}, and core-app skips the duplicate. Delivery is at least once, never zero.</li>
 * </ul>
 * The row locks held during the send are on outbox rows only, never on a wallet or a case, so the "no remote call
 * while holding locks" rule (CLAUDE.md) is about a different kind of lock; nothing else waits for these rows.
 */
@Service
public class FreezeRequestRelay {

    private static final Logger log = LoggerFactory.getLogger(FreezeRequestRelay.class);

    private final PendingFreezeRequests pending;
    private final FreezeRequestPublisher publisher;
    private final FreezeRelayProperties properties;
    private final Clock clock;

    public FreezeRequestRelay(PendingFreezeRequests pending, FreezeRequestPublisher publisher,
            FreezeRelayProperties properties, Clock clock) {
        this.pending = pending;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return how many requests were sent in this run */
    @Transactional
    public int relayPending() {
        List<Pending> batch = pending.claim(properties.batchSize());
        int sent = 0;
        for (Pending request : batch) {
            var event = request.event();
            try {
                publisher.publish(event, request.correlationId());
            } catch (RuntimeException e) {
                pending.markFailed(event.eventId());
                log.warn("Freeze request not sent; will retry request={} wallet={} attempt={} error={}",
                        event.eventId(), event.walletId(), request.attempts() + 1, e.getClass().getSimpleName());
                break;
            }
            pending.markPublished(event.eventId(), clock.instant());
            sent++;
            log.info("Freeze request sent request={} wallet={} case={}", event.eventId(), event.walletId(),
                    event.caseId());
        }
        return sent;
    }
}
