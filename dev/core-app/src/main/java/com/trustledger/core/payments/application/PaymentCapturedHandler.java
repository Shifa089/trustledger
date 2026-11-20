package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.WebhookEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code payment.captured} (FR-P-4): credits the top-up of the payment's order, or parks it in suspense (ADR 0006).
 * Runs inside {@link WebhookProcessor}'s transaction, so the credit and {@code processed_at} commit together. A
 * signed event with an unreadable payment is logged and marked processed: retrying it would never succeed.
 */
@Component
class PaymentCapturedHandler implements WebhookEventHandler {

    static final String TYPE = "payment.captured";

    private static final Logger log = LoggerFactory.getLogger(PaymentCapturedHandler.class);

    private final WebhookPayloads payloads;
    private final TopupSettlementService settlement;

    PaymentCapturedHandler(WebhookPayloads payloads, TopupSettlementService settlement) {
        this.payloads = payloads;
        this.settlement = settlement;
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public void handle(WebhookEvent event) {
        Optional<GatewayPayment> payment = payloads.payment(event.payload());
        if (payment.isEmpty() || payment.get().status() != GatewayPaymentStatus.CAPTURED) {
            log.warn("Webhook event {} ({}) ignored: no captured payment on an order in the payload",
                    event.gatewayEventId(), TYPE);
            return;
        }
        settlement.capture(payment.get());
    }
}
