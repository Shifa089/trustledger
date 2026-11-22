package com.trustledger.aml.shared.kafka;

import static com.trustledger.aml.Eventually.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.aml.KafkaTestClient;
import com.trustledger.aml.TestTelemetry;
import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.events.AccountFreezeRequestedV1;
import com.trustledger.events.TransferCompletedV1;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * M12, risk-aml-service's half of "one transfer, one trace": a {@code TransferCompletedV1} that arrives with core-app's
 * W3C {@code traceparent} is consumed in a consumer span of that same trace, a child of core-app's producer span. And
 * this service's own sends (the freeze-request template, built by hand in {@link KafkaConsumerConfig}) write a
 * {@code traceparent} of the current trace.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaTracingIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    KafkaContainer kafka;

    @Autowired
    TestTelemetry telemetry;

    @Autowired
    ObservationRegistry observations;

    @Autowired
    Tracer tracer;

    @Autowired
    @Qualifier("freezeRequestTemplate")
    KafkaTemplate<String, Object> freezeRequestTemplate;

    private KafkaTestClient client;

    @BeforeEach
    void setUp() {
        client = new KafkaTestClient(kafka);
        telemetry.start();
    }

    @AfterEach
    void tearDown() {
        telemetry.stop();
        client.close();
    }

    @Test
    void aTransferFromCoreAppIsConsumedInTheSendersTrace() {
        String traceId = randomHex(16);
        String producerSpanId = randomHex(8);
        TransferCompletedV1 transfer = new TransferCompletedV1(UUID.randomUUID(), Instant.now(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 12_000, "INR");

        client.sendContract(TransferCompletedV1.TOPIC, transfer.walletId().toString(), transfer,
                transfer.eventId(), Map.of("traceparent", "00-" + traceId + "-" + producerSpanId + "-01"));

        await(WAIT, "a consumer span in core-app's trace", () -> telemetry.spansOfTrace(traceId).stream()
                .anyMatch(span -> span.getKind() == SpanKind.CONSUMER));
        assertThat(telemetry.spansOfTrace(traceId)).filteredOn(span -> span.getKind() == SpanKind.CONSUMER)
                .first().satisfies(span -> {
                    assertThat(span.getName()).contains(TransferCompletedV1.TOPIC);
                    assertThat(span.getParentSpanId()).as("a child of core-app's producer span")
                            .isEqualTo(producerSpanId);
                });
    }

    @Test
    void aFreezeRequestIsSentWithTheCurrentTraceContext() throws Exception {
        UUID wallet = UUID.randomUUID();
        AccountFreezeRequestedV1 request = new AccountFreezeRequestedV1(UUID.randomUUID(), Instant.now(), wallet,
                UUID.randomUUID(), "R2");

        Observation relay = Observation.start("test.freeze-relay", observations);
        String traceId;
        try (Observation.Scope scope = relay.openScope()) {
            traceId = tracer.currentSpan().context().traceId();
            freezeRequestTemplate.send(AccountFreezeRequestedV1.TOPIC, wallet.toString(), request).get();
        } finally {
            relay.stop();
        }

        assertThat(client.readByKey(AccountFreezeRequestedV1.TOPIC, wallet.toString(), WAIT, Duration.ZERO))
                .singleElement().satisfies(record -> assertThat(KafkaTestClient.header(record, "traceparent"))
                        .matches("00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}"));
        await(WAIT, "a producer span in the relay's trace", () -> telemetry.spansOfTrace(traceId).stream()
                .anyMatch(span -> span.getKind() == SpanKind.PRODUCER));
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
