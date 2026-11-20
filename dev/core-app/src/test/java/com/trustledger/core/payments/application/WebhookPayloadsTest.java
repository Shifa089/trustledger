package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.payments.application.WebhookPayloads.Parsed;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class WebhookPayloadsTest {

    private final WebhookPayloads payloads = new WebhookPayloads(JsonMapper.builder().build());

    private Optional<Parsed> parse(String body) {
        return payloads.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Shape of a real Razorpay payment.captured event, with fake values. */
    private static final String CAPTURED = """
            {"entity": "event", "account_id": "acc_FAKE", "event": "payment.captured",
             "contains": ["payment"],
             "payload": {"payment": {"entity": {
                "id": "pay_FAKE0000000001", "order_id": "order_FAKE000000001", "amount": 50000, "currency": "INR",
                "status": "captured", "method": "card",
                "email": "customer.one@example.test", "contact": "+919999999999", "vpa": "someone@upi",
                "card": {"last4": "1111", "name": "Test Customer"},
                "notes": [{"email": "nested@example.test"}]}}},
             "created_at": 1763632800}""";

    @Test
    void readsTheEventTypeAndKeepsWhatProcessingNeeds() {
        Parsed parsed = parse(CAPTURED).orElseThrow();
        assertThat(parsed.eventType()).isEqualTo("payment.captured");
        assertThat(parsed.json()).contains("pay_FAKE0000000001", "order_FAKE000000001", "50000", "captured");
    }

    @Test
    void removesPersonalFieldsAtAnyDepth() {
        String json = parse(CAPTURED).orElseThrow().json();
        assertThat(json).doesNotContain("example.test", "+919999999999", "someone@upi", "Test Customer", "1111",
                "\"email\":", "\"contact\":", "\"vpa\":", "\"card\":");
        assertThat(json).as("a value that merely equals a field name is kept").contains("\"method\":\"card\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[1,2,3]", "\"payment.captured\"", "42", "null", "{\"event\": "})
    void bodiesThatAreNotAJsonObjectGiveNothing(String body) {
        assertThat(parse(body)).isEmpty();
    }

    @Test
    void nulCharacterCannotBeStoredAsJsonbSoGivesNothing() {
        assertThat(parse("{\"event\":\"payment.captured\",\"x\":\"a\\u0000b\"}")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"event\": 42}", "{\"event\": \"\"}", "{\"event\": \"Payment Captured\"}",
            "{\"event\": \"payment.captured\\n\"}", "{\"event\": \"a.b.c.d.e\"}", "{\"event\": {\"x\":1}}"})
    void malformedOrMissingTypeIsNullButTheJsonIsKept(String body) {
        Parsed parsed = parse(body).orElseThrow();
        assertThat(parsed.eventType()).isNull();
        assertThat(parsed.json()).startsWith("{");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment.captured", "payment.failed", "order.paid", "refund.processed",
            "payment.dispute.created", "unknown_type"})
    void wellFormedTypesAreRead(String type) {
        assertThat(parse("{\"event\":\"" + type + "\"}").orElseThrow().eventType()).isEqualTo(type);
    }
}
