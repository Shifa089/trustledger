package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.WebhookEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code payment.failed}: a PENDING top-up becomes FAILED. Any other status is left alone, so a failure that arrives
 * after the capture changes nothing. If the customer then pays the same order successfully, the later
 * {@code payment.captured} still credits it (late capture, {@link TopupSettlementService#capture}).
 */
@Component
class PaymentFailedHandler implements WebhookEventHandler {

    static final String TYPE = "payment.failed";

    private static final Logger log = LoggerFactory.getLogger(PaymentFailedHandler.class);

    private final WebhookPayloads payloads;
    private final TopupSettlementService settlement;

    PaymentFailedHandler(WebhookPayloads payloads, TopupSettlementService settlement) {
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
        if (payment.isEmpty()) {
            log.warn("Webhook event {} ({}) ignored: no payment on an order in the payload", event.gatewayEventId(),
                    TYPE);
            return;
        }
        settlement.fail(payment.get());
    }
}
