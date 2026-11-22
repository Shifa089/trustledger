package com.trustledger.aml.cases.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.aml.cases.domain.FreezeRequestPublisher;
import com.trustledger.aml.cases.domain.FreezeRequestPublisher.FreezeRequestPublishException;
import com.trustledger.aml.cases.domain.PendingFreezeRequests;
import com.trustledger.aml.cases.domain.PendingFreezeRequests.Pending;
import com.trustledger.events.AccountFreezeRequestedV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FreezeRequestRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final PendingFreezeRequests pending = mock(PendingFreezeRequests.class);
    private final FreezeRequestPublisher publisher = mock(FreezeRequestPublisher.class);
    private final FreezeRequestRelay relay = new FreezeRequestRelay(pending, publisher,
            new FreezeRelayProperties(Duration.ofSeconds(1), 50, Duration.ofSeconds(10)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void sendsEachPendingRequestAndThenMarksItPublished() {
        Pending first = pending("corr-1");
        Pending second = pending(null);
        when(pending.claim(50)).thenReturn(List.of(first, second));

        assertThat(relay.relayPending()).isEqualTo(2);

        InOrder order = inOrder(publisher, pending);
        order.verify(publisher).publish(first.event(), "corr-1");
        order.verify(pending).markPublished(first.event().eventId(), NOW);
        order.verify(publisher).publish(second.event(), null);
        order.verify(pending).markPublished(second.event().eventId(), NOW);
    }

    @Test
    void aFailedSendLeavesTheRequestPendingAndStopsTheRun() {
        Pending first = pending("corr-1");
        Pending second = pending("corr-2");
        when(pending.claim(anyInt())).thenReturn(List.of(first, second));
        doThrow(new FreezeRequestPublishException("broker down", null))
                .when(publisher).publish(eq(first.event()), any());

        assertThat(relay.relayPending()).isZero();

        verify(pending).markFailed(first.event().eventId());
        verify(pending, never()).markPublished(any(), any());
        // The broker is probably down: the second request waits for the next run instead of another timeout.
        verify(publisher, never()).publish(eq(second.event()), any());
    }

    @Test
    void nothingPendingSendsNothing() {
        when(pending.claim(anyInt())).thenReturn(List.of());

        assertThat(relay.relayPending()).isZero();

        verify(publisher, never()).publish(any(), any());
    }

    private static Pending pending(String correlationId) {
        return new Pending(new AccountFreezeRequestedV1(UUID.randomUUID(), NOW.minusSeconds(5), UUID.randomUUID(),
                UUID.randomUUID(), "R2"), correlationId, 0);
    }
}
