package com.trustledger.aml.cases.application;

import com.trustledger.aml.cases.domain.CaseEventPublisher;
import com.trustledger.aml.cases.domain.PendingCaseEvents;
import com.trustledger.aml.cases.domain.PendingCaseEvents.Pending;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox relay for case events (ADR 0007): the same algorithm as {@link FreezeRequestRelay} and the same settings
 * ({@code trustledger.aml.freeze-relay.*}). Claims a batch with {@code SKIP LOCKED}, sends each event and waits for the
 * broker, marks it published. A failed send counts an attempt and ends the run, so a later event of the same case is
 * never sent before an earlier one. Delivery is at least once with a stable {@code eventId}.
 */
@Service
public class CaseEventRelay {

    private static final Logger log = LoggerFactory.getLogger(CaseEventRelay.class);

    private final PendingCaseEvents pending;
    private final CaseEventPublisher publisher;
    private final FreezeRelayProperties properties;
    private final Clock clock;

    public CaseEventRelay(PendingCaseEvents pending, CaseEventPublisher publisher, FreezeRelayProperties properties,
            Clock clock) {
        this.pending = pending;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return how many events were sent in this run */
    @Transactional
    public int relayPending() {
        List<Pending> batch = pending.claim(properties.batchSize());
        int sent = 0;
        for (Pending item : batch) {
            var event = item.event();
            try {
                publisher.publish(event, item.correlationId());
            } catch (RuntimeException e) {
                pending.markFailed(event.eventId());
                log.warn("Case event not sent; will retry event={} case={} attempt={} error={}", event.eventId(),
                        event.caseId(), item.attempts() + 1, e.getClass().getSimpleName());
                break;
            }
            pending.markPublished(event.eventId(), clock.instant());
            sent++;
            log.info("Case event sent event={} case={} status={}", event.eventId(), event.caseId(), event.status());
        }
        return sent;
    }
}
