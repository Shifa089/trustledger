package com.trustledger.aml.facts.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class TransactionFactListenerTest {

    @Test
    void readsAPlainCorrelationId() {
        assertThat(TransactionFactListener.correlationId(record("3f2a9c1e-0b7d-4c55-9a1e-2b3c4d5e6f70")))
                .isEqualTo("3f2a9c1e-0b7d-4c55-9a1e-2b3c4d5e6f70");
    }

    @Test
    void ignoresAMissingOrUnsafeCorrelationId() {
        assertThat(TransactionFactListener.correlationId(new ConsumerRecord<>("t", 0, 0, "k", "v"))).isNull();
        assertThat(TransactionFactListener.correlationId(record("abc\nFAKE LOG LINE"))).isNull();
        assertThat(TransactionFactListener.correlationId(record("<script>"))).isNull();
        assertThat(TransactionFactListener.correlationId(record("x".repeat(65)))).isNull();
    }

    private static ConsumerRecord<String, String> record(String correlationId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0, "k", "v");
        record.headers().add(TransactionFactListener.CORRELATION_ID_HEADER,
                correlationId.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
