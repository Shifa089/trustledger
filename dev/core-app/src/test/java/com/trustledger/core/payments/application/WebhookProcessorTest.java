package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.domain.WebhookEvent;
import com.trustledger.core.payments.domain.WebhookEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class WebhookProcessorTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant NOW = RECEIVED.plusSeconds(2);

    /** Records what it handled; can be told to fail. */
    static final class Recording implements WebhookEventHandler {
        final String type;
        final List<WebhookEvent> handled = new ArrayList<>();
        RuntimeException failure;

        Recording(String type) {
            this.type = type;
        }

        @Override
        public String eventType() {
            return type;
        }

        @Override
        public void handle(WebhookEvent event) {
            if (failure != null) {
                throw failure;
            }
            handled.add(event);
        }
    }

    private final WebhookEventRepository events = mock(WebhookEventRepository.class);
    private final Recording captured = new Recording("payment.captured");

    private WebhookProcessor processor(WebhookEventHandler... handlers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < handlers.length; i++) {
            beans.addBean("handler" + i, handlers[i]);
        }
        return new WebhookProcessor(events, beans.getBeanProvider(WebhookEventHandler.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private WebhookEvent stored(String type) {
        WebhookEvent event = WebhookEvent.accepted(UUID.randomUUID(), "evt_1", type, "{}", RECEIVED);
        when(events.findAcceptedForUpdate("evt_1")).thenReturn(Optional.of(event));
        when(events.markProcessed(event.id(), NOW)).thenReturn(true);
        return event;
    }

    @Test
    void dispatchesByTypeThenMarksProcessed() {
        WebhookEvent event = stored("payment.captured");
        Recording failed = new Recording("payment.failed");

        assertThat(processor(captured, failed).process("evt_1")).isEqualTo(WebhookOutcome.PROCESSED);

        assertThat(captured.handled).containsExactly(event);
        assertThat(failed.handled).isEmpty();
        verify(events).markProcessed(event.id(), NOW);
    }

    @Test
    void locksTheRowBeforeHandlingAndMarksAfter() {
        WebhookEvent event = stored("payment.captured");
        WebhookEventHandler handler = mock(WebhookEventHandler.class);
        when(handler.eventType()).thenReturn("payment.captured");

        processor(handler).process("evt_1");

        InOrder order = inOrder(events, handler);
        order.verify(events).findAcceptedForUpdate("evt_1");
        order.verify(handler).handle(event);
        order.verify(events).markProcessed(event.id(), NOW);
    }

    @Test
    void unknownTypeIsIgnoredButMarkedProcessed() {
        WebhookEvent event = stored("refund.processed");

        assertThat(processor(captured).process("evt_1")).isEqualTo(WebhookOutcome.IGNORED);

        assertThat(captured.handled).isEmpty();
        verify(events).markProcessed(event.id(), NOW);
    }

    @Test
    void withNoHandlersAtAllEverythingIsIgnored() {
        stored("payment.captured");
        assertThat(processor().process("evt_1")).isEqualTo(WebhookOutcome.IGNORED);
    }

    @Test
    void alreadyProcessedIsADuplicateAndNothingRuns() {
        WebhookEvent processed = WebhookEvent.accepted(UUID.randomUUID(), "evt_1", "payment.captured", "{}",
                RECEIVED).markProcessed(RECEIVED.plusSeconds(1));
        when(events.findAcceptedForUpdate("evt_1")).thenReturn(Optional.of(processed));

        assertThat(processor(captured).process("evt_1")).isEqualTo(WebhookOutcome.DUPLICATE);

        assertThat(captured.handled).isEmpty();
        verify(events, never()).markProcessed(any(), any());
    }

    @Test
    void handlerFailureLeavesTheEventUnprocessed() {
        stored("payment.captured");
        captured.failure = new IllegalStateException("ledger down");

        assertThatThrownBy(() -> processor(captured).process("evt_1")).hasMessage("ledger down");

        verify(events, never()).markProcessed(any(), any());
    }

    @Test
    void missingEventIsAProgrammingError() {
        when(events.findAcceptedForUpdate("evt_1")).thenReturn(Optional.empty());
        assertThatIllegalStateException().isThrownBy(() -> processor(captured).process("evt_1"));
    }

    @Test
    void rowChangedUnderTheLockIsAProgrammingError() {
        WebhookEvent event = stored("payment.captured");
        when(events.markProcessed(event.id(), NOW)).thenReturn(false);
        assertThatIllegalStateException().isThrownBy(() -> processor(captured).process("evt_1"));
    }

    @Test
    void twoHandlersForOneTypeFailStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> processor(captured, new Recording("payment.captured")))
                .withMessageContaining("payment.captured");
    }
}
