package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A row of {@code payments.webhook_events} (FR-P-5): one delivery of a gateway webhook.
 * <p>
 * {@code signatureValid} means the delivery was <em>accepted</em>: the HMAC signature matched and it carried an
 * event ID, a type and a JSON body. Accepted events are de-duplicated by {@code gatewayEventId} and processed once
 * ({@code processedAt}). Rejected deliveries are stored as evidence and never processed. The same invariants are
 * database checks ({@code V14}).
 *
 * @param gatewayEventId Razorpay's event ID ({@code X-Razorpay-Event-Id}); may be null on a rejected delivery
 * @param eventType      e.g. {@code payment.captured}; may be null on a rejected delivery
 * @param payload        the JSON body with personal fields removed; may be null on a rejected delivery
 * @param processedAt    when processing committed; null until then (always null when rejected)
 */
public record WebhookEvent(UUID id, String gatewayEventId, String eventType, String payload, boolean signatureValid,
        Instant receivedAt, Instant processedAt) {

    public WebhookEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (signatureValid && (gatewayEventId == null || eventType == null || payload == null)) {
            throw new IllegalArgumentException("An accepted webhook event needs an event ID, a type and a payload");
        }
        if (!signatureValid && processedAt != null) {
            throw new IllegalArgumentException("A rejected webhook delivery is never processed");
        }
    }

    /** An accepted (signature-verified) event, not processed yet. */
    public static WebhookEvent accepted(UUID id, String gatewayEventId, String eventType, String payload,
            Instant receivedAt) {
        return new WebhookEvent(id, gatewayEventId, eventType, payload, true, receivedAt, null);
    }

    /** A rejected delivery: kept as evidence, never processed. Any of the three values may be null. */
    public static WebhookEvent rejected(UUID id, String gatewayEventId, String eventType, String payload,
            Instant receivedAt) {
        return new WebhookEvent(id, gatewayEventId, eventType, payload, false, receivedAt, null);
    }

    public boolean isProcessed() {
        return processedAt != null;
    }

    /**
     * This event, processed at {@code now}.
     *
     * @throws TrustLedgerException {@code INVALID_STATE_TRANSITION} when rejected or already processed
     */
    public WebhookEvent markProcessed(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!signatureValid) {
            throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A rejected webhook delivery cannot be processed.");
        }
        if (isProcessed()) {
            throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The webhook event was already processed.");
        }
        return new WebhookEvent(id, gatewayEventId, eventType, payload, true, receivedAt, now);
    }

    /** Never prints the payload. */
    @Override
    public String toString() {
        return "WebhookEvent[id=" + id + ", gatewayEventId=" + gatewayEventId + ", eventType=" + eventType
                + ", signatureValid=" + signatureValid + ", receivedAt=" + receivedAt + ", processedAt="
                + processedAt + "]";
    }
}
