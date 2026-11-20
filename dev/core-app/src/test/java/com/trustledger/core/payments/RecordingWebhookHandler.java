package com.trustledger.core.payments;

import com.trustledger.core.payments.application.WebhookEventHandler;
import com.trustledger.core.payments.domain.WebhookEvent;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Test-only handler for the made-up event type {@value #TYPE}, so the webhook ITs can see how often an event was
 * really processed before the real {@code payment.captured} handler exists (M7 part 3b). It can be told to fail for
 * a given event ID.
 * <p>
 * Lives in test sources only; component scanning picks it up in every IT context, which is harmless: no real
 * gateway event has this type.
 */
@Component
class RecordingWebhookHandler implements WebhookEventHandler {

    static final String TYPE = "test.recorded";

    private final Map<String, AtomicInteger> handled = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public void handle(WebhookEvent event) {
        if (failing.contains(event.gatewayEventId())) {
            throw new IllegalStateException("Simulated processing failure for " + event.gatewayEventId());
        }
        handled.computeIfAbsent(event.gatewayEventId(), id -> new AtomicInteger()).incrementAndGet();
    }

    int timesHandled(String eventId) {
        AtomicInteger count = handled.get(eventId);
        return count == null ? 0 : count.get();
    }

    void failFor(String eventId) {
        failing.add(eventId);
    }

    void succeedFor(String eventId) {
        failing.remove(eventId);
    }
}
