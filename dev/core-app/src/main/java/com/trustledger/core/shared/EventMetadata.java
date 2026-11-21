package com.trustledger.core.shared;

import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Identity of one published domain event, fixed when it is published (M8).
 * <p>
 * Why it is part of the event and not made up later: Spring Modulith stores the event in {@code event_publication}
 * (the outbox) and, after a crash or a failed send, resubmits the <b>stored</b> event, mapping it to its Kafka
 * contract again. An ID generated during that mapping would differ on every resubmission, and the consumers'
 * de-duplication ({@code eventId}) would see a re-sent message as a new one. Created here, it is serialized with
 * the event and survives a restart.
 *
 * @param eventId       UUIDv7 ({@link Ids#newV7}); becomes the contract's {@code eventId}
 * @param correlationId the request's correlation ID from the MDC; {@code null} when published outside a request
 *                      (scheduled jobs). Sent as a Kafka header, never in the payload
 */
public record EventMetadata(UUID eventId, String correlationId) {

    public EventMetadata {
        Objects.requireNonNull(eventId, "eventId");
    }

    /** New metadata for an event about to be published on this thread. */
    public static EventMetadata capture(Clock clock) {
        return new EventMetadata(Ids.newV7(clock), MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
