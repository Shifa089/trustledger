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

import com.trustledger.aml.cases.domain.CaseEventPublisher;
import com.trustledger.aml.cases.domain.PendingCaseEvents;
import com.trustledger.aml.cases.domain.PendingCaseEvents.Pending;
import com.trustledger.events.AmlCaseStatusChangedV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CaseEventRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final PendingCaseEvents pending = mock(PendingCaseEvents.class);
    private final CaseEventPublisher publisher = mock(CaseEventPublisher.class);
    private final CaseEventRelay relay = new CaseEventRelay(pending, publisher,
            new FreezeRelayProperties(Duration.ofSeconds(1), 50, Duration.ofSeconds(10)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void sendsEachPendingEventInOrderAndMarksItPublished() {
        UUID caseId = UUID.randomUUID();
        Pending opened = pending(caseId, "OPEN", true, "corr-1");
        Pending closed = pending(caseId, "CLOSED_FALSE_POSITIVE", false, null);
        when(pending.claim(50)).thenReturn(List.of(opened, closed));

        assertThat(relay.relayPending()).isEqualTo(2);

        InOrder order = inOrder(publisher, pending);
        order.verify(publisher).publish(opened.event(), "corr-1");
        order.verify(pending).markPublished(opened.event().eventId(), NOW);
        order.verify(publisher).publish(closed.event(), null);
        order.verify(pending).markPublished(closed.event().eventId(), NOW);
    }

    @Test
    void aFailedSendKeepsTheEventPendingAndNeverSendsALaterOneFirst() {
        UUID caseId = UUID.randomUUID();
        Pending opened = pending(caseId, "OPEN", true, null);
        Pending closed = pending(caseId, "ESCALATED", false, null);
        when(pending.claim(anyInt())).thenReturn(List.of(opened, closed));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(eq(opened.event()), any());

        assertThat(relay.relayPending()).isZero();

        verify(pending).markFailed(opened.event().eventId());
        verify(pending, never()).markPublished(any(), any());
        verify(publisher, never()).publish(eq(closed.event()), any());
    }

    private static Pending pending(UUID caseId, String status, boolean open, String correlationId) {
        return new Pending(new AmlCaseStatusChangedV1(UUID.randomUUID(), NOW.minusSeconds(5), UUID.randomUUID(),
                caseId, status, open), correlationId, 0);
    }
}
