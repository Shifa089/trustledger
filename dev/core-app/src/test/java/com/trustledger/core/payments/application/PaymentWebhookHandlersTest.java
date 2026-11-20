package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.WebhookEvent;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentWebhookHandlersTest {

    private final WebhookPayloads payloads = new WebhookPayloads(JsonMapper.builder().build());
    private final TopupSettlementService settlement = mock(TopupSettlementService.class);
    private final PaymentCapturedHandler captured = new PaymentCapturedHandler(payloads, settlement);
    private final PaymentFailedHandler failed = new PaymentFailedHandler(payloads, settlement);

    private static WebhookEvent event(String type, String status) {
        String payload = """
                {"event":"%s","payload":{"payment":{"entity":{"id":"pay_FAKE0000000001",
                 "order_id":"order_FAKE000000001","amount":50000,"currency":"INR","status":"%s"}}}}"""
                .formatted(type, status);
        return WebhookEvent.accepted(UUID.randomUUID(), "evt_1", type, payload, Instant.parse("2026-11-20T10:00:00Z"));
    }

    private static GatewayPayment payment(GatewayPaymentStatus status) {
        return new GatewayPayment("pay_FAKE0000000001", "order_FAKE000000001", Money.parse("500.00"), status, null);
    }

    @Test
    void handlersAreRegisteredForRazorpaysEventTypes() {
        assertThat(captured.eventType()).isEqualTo("payment.captured");
        assertThat(failed.eventType()).isEqualTo("payment.failed");
    }

    @Test
    void capturedEventSettlesThePayment() {
        captured.handle(event("payment.captured", "captured"));
        verify(settlement).capture(payment(GatewayPaymentStatus.CAPTURED));
    }

    @Test
    void capturedEventWhosePaymentIsNotCapturedIsIgnored() {
        captured.handle(event("payment.captured", "authorized"));
        verify(settlement, never()).capture(any());
    }

    @Test
    void capturedEventWithoutAReadablePaymentIsIgnored() {
        captured.handle(WebhookEvent.accepted(UUID.randomUUID(), "evt_2", "payment.captured",
                "{\"event\":\"payment.captured\",\"payload\":{}}", Instant.parse("2026-11-20T10:00:00Z")));
        verify(settlement, never()).capture(any());
    }

    @Test
    void failedEventFailsThePayment() {
        failed.handle(event("payment.failed", "failed"));
        verify(settlement).fail(payment(GatewayPaymentStatus.FAILED));
    }

    @Test
    void failedEventWithoutAReadablePaymentIsIgnored() {
        failed.handle(WebhookEvent.accepted(UUID.randomUUID(), "evt_3", "payment.failed", "{}",
                Instant.parse("2026-11-20T10:00:00Z")));
        verify(settlement, never()).fail(any());
    }
}
