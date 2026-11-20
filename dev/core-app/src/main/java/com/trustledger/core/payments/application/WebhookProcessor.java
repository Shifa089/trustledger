package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.WebhookEvent;
import com.trustledger.core.payments.domain.WebhookEventRepository;
import java.time.Clock;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes one stored, accepted webhook event in its <strong>own</strong> transaction (FR-P-5):
 * <ol>
 * <li>lock the event row ({@code FOR UPDATE}): a concurrent delivery of the same event waits here;</li>
 * <li>already processed → {@link WebhookOutcome#DUPLICATE}, nothing done;</li>
 * <li>run the handler for its type, if any (none → {@link WebhookOutcome#IGNORED});</li>
 * <li>set {@code processed_at}, which commits together with the handler's work.</li>
 * </ol>
 * If the handler throws, everything rolls back and the event stays unprocessed, so the gateway's retry of the same
 * event runs it again. "Duplicate" therefore means "already processed", not "already stored".
 */
@Service
public class WebhookProcessor {

    private final WebhookEventRepository events;
    private final Map<String, WebhookEventHandler> handlers;
    private final Clock clock;

    WebhookProcessor(WebhookEventRepository events, ObjectProvider<WebhookEventHandler> handlers, Clock clock) {
        this.events = events;
        this.handlers = handlers.orderedStream().collect(Collectors.toUnmodifiableMap(
                WebhookEventHandler::eventType, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Two webhook handlers for event type " + a.eventType());
                }));
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookOutcome process(String gatewayEventId) {
        WebhookEvent event = events.findAcceptedForUpdate(gatewayEventId)
                .orElseThrow(() -> new IllegalStateException("Webhook event " + gatewayEventId + " is not stored"));
        if (event.isProcessed()) {
            return WebhookOutcome.DUPLICATE;
        }
        WebhookEventHandler handler = handlers.get(event.eventType());
        if (handler != null) {
            handler.handle(event);
        }
        WebhookEvent processed = event.markProcessed(clock.instant());
        if (!events.markProcessed(processed.id(), processed.processedAt())) {
            throw new IllegalStateException("Webhook event " + gatewayEventId + " changed while locked");
        }
        return handler == null ? WebhookOutcome.IGNORED : WebhookOutcome.PROCESSED;
    }
}
