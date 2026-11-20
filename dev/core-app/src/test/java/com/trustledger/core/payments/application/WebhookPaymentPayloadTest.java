package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** {@link WebhookPayloads#payment}: reading the payment out of a stored {@code payment.*} event. */
class WebhookPaymentPayloadTest {

    private final WebhookPayloads payloads = new WebhookPayloads(JsonMapper.builder().build());

    private static String event(String entity) {
        return """
                {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":%s}}}""".formatted(entity);
    }

    private static final String VALID = """
            {"id":"pay_FAKE0000000001","order_id":"order_FAKE000000001","amount":50000,"currency":"INR",
             "status":"captured","method":"upi"}""";

    @Test
    void readsIdsAmountAndStatus() {
        GatewayPayment payment = payloads.payment(event(VALID)).orElseThrow();
        assertThat(payment.paymentId()).isEqualTo("pay_FAKE0000000001");
        assertThat(payment.orderId()).isEqualTo("order_FAKE000000001");
        assertThat(payment.amount()).isEqualTo(Money.parse("500.00"));
        assertThat(payment.status()).isEqualTo(GatewayPaymentStatus.CAPTURED);
        assertThat(payment.errorCode()).isNull();
    }

    @Test
    void readsAFailedAttemptWithItsErrorCode() {
        GatewayPayment payment = payloads.payment(event("""
                {"id":"pay_FAKE0000000002","order_id":"order_FAKE000000001","amount":50000,"currency":"INR",
                 "status":"failed","error_code":"BAD_REQUEST_ERROR"}""")).orElseThrow();
        assertThat(payment.status()).isEqualTo(GatewayPaymentStatus.FAILED);
        assertThat(payment.errorCode()).isEqualTo("BAD_REQUEST_ERROR");
    }

    @Test
    void anErrorCodeThatIsNotACodeIsDropped() {
        GatewayPayment payment = payloads.payment(event("""
                {"id":"pay_FAKE0000000002","order_id":"order_FAKE000000001","amount":50000,"currency":"INR",
                 "status":"failed","error_code":"call me on 99999 99999"}""")).orElseThrow();
        assertThat(payment.errorCode()).isNull();
    }

    @Test
    void anUnknownStatusIsUnknownNotAnError() {
        GatewayPayment payment = payloads.payment(event(VALID.replace("captured", "something_new"))).orElseThrow();
        assertThat(payment.status()).isEqualTo(GatewayPaymentStatus.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"order_id\":\"order_FAKE000000001\",\"amount\":50000,\"currency\":\"INR\",\"status\":\"captured\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"amount\":50000,\"currency\":\"INR\",\"status\":\"captured\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":null,\"amount\":50000,\"currency\":\"INR\"}",
            "{\"id\":\"order_FAKE00000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":50000,\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"pay_FAKE000000001\",\"amount\":50000,\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":\"500\",\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":500.5,\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":0,\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":-1,\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":99999999999999999999,\"currency\":\"INR\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":50000}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":50000,\"currency\":\"inr\"}",
            "{\"id\":\"pay_FAKE0000000001\",\"order_id\":\"order_FAKE000000001\",\"amount\":50000,\"currency\":\"XQZ\"}",
            "\"not an object\""
    })
    void malformedPaymentsAreNotRead(String entity) {
        assertThat(payloads.payment(event(entity))).isEmpty();
    }

    @Test
    void eventsWithoutAPaymentAreNotRead() {
        assertThat(payloads.payment("{\"event\":\"refund.processed\",\"payload\":{\"refund\":{}}}")).isEmpty();
        assertThat(payloads.payment("{}")).isEmpty();
        assertThat(payloads.payment("not json")).isEmpty();
    }

    @Test
    void readsWhatParseStored() {
        String stored = payloads.parse(event(VALID.replace("\"method\":\"upi\"",
                "\"email\":\"customer.one@example.test\"")).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .orElseThrow().json();
        assertThat(payloads.payment(stored)).isPresent();
    }
}
