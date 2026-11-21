package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class EventMetadataTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:15:30.123Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void eventIdIsAUuidV7FromTheInjectedClock() {
        UUID eventId = EventMetadata.capture(clock).eventId();

        assertThat(eventId.version()).isEqualTo(7);
        assertThat(eventId.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void everyCaptureGetsANewEventId() {
        assertThat(EventMetadata.capture(clock).eventId()).isNotEqualTo(EventMetadata.capture(clock).eventId());
    }

    @Test
    void correlationIdComesFromTheRequestMdc() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");

        assertThat(EventMetadata.capture(clock).correlationId()).isEqualTo("corr-123");
    }

    @Test
    void correlationIdIsNullOutsideARequest() {
        assertThat(EventMetadata.capture(clock).correlationId()).isNull();
    }

    @Test
    void eventIdIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new EventMetadata(null, "corr")).withMessage("eventId");
    }
}
