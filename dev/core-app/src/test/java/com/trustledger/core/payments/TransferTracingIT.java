package com.trustledger.core.payments;

import static com.trustledger.core.Eventually.eventually;
import static com.trustledger.core.KafkaTestConsumer.header;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.KafkaTestConsumer;
import com.trustledger.core.TestTelemetry;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.events.TransferCompletedV1;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * M12: a transfer's trace reaches Kafka. The transfer runs inside a parent observation (standing in for the HTTP
 * request's span); the outbox sends {@code TransferCompletedV1} after the commit, on another thread
 * ({@code spring.task.execution.propagate-context}), through Boot's observed KafkaTemplate. The record's W3C
 * {@code traceparent} header carries the same trace ID, which is what lets risk-aml-service's consumer span join
 * this trace.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferTracingIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    TransferService transfers;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    ObservationRegistry observations;

    @Autowired
    Tracer tracer;

    @Autowired
    TestTelemetry telemetry;

    @Autowired
    KafkaContainer kafka;

    @BeforeEach
    void record() {
        telemetry.start();
    }

    @AfterEach
    void stopRecording() {
        telemetry.stop();
    }

    @Test
    void theKafkaRecordOfATransferCarriesTheTransfersTraceContext() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        Observation request = Observation.start("test.transfer-request", observations);
        String traceId;
        try (Observation.Scope scope = request.openScope()) {
            traceId = tracer.currentSpan().context().traceId();
            TransferResult result = transfers.transfer(new TransferCommand(sender.userId(),
                    TransferRecipient.byWalletId(receiver.walletId()), Money.parse("120.00"), "Tracing test"));
            assertThat(result.transferId()).isNotNull();
        } finally {
            request.stop();
        }

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(kafka.getBootstrapServers(),
                TransferCompletedV1.TOPIC)) {
            ConsumerRecord<String, String> record =
                    consumer.recordsWithKey(sender.walletId().toString(), 1, WAIT).getFirst();
            assertThat(header(record, "traceparent")).as("W3C trace context in the Kafka headers")
                    .matches("00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}");
        }
        eventually(() -> assertThat(telemetry.spansOfTrace(traceId))
                .as("a producer span for the send, in the transfer's trace")
                .anySatisfy(span -> {
                    assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
                    assertThat(span.getName()).contains(TransferCompletedV1.TOPIC);
                }));
    }
}
