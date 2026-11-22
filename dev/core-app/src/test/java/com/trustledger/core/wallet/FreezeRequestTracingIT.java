package com.trustledger.core.wallet;

import static com.trustledger.core.Eventually.eventually;
import static com.trustledger.core.KafkaTestConsumer.header;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.KafkaTestConsumer;
import com.trustledger.core.TestTelemetry;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.events.AccountFreezeRequestedV1;
import io.opentelemetry.api.trace.SpanKind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * M12, the consuming side in core-app: a record that arrives with a W3C {@code traceparent} (as risk-aml-service's
 * observed template writes it) is handled in a consumer span of that same trace, a child of the sender's span. A
 * record that ends in the dead-letter topic keeps the trace too, because the dead-letter template is observed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FreezeRequestTracingIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String DLT = AccountFreezeRequestedV1.TOPIC + ".DLT";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WalletFixtures fixtures;

    @Autowired
    WalletApi wallets;

    @Autowired
    TestTelemetry telemetry;

    @Autowired
    KafkaContainer kafka;

    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void setUp() {
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new ByteArraySerializer());
        telemetry.start();
    }

    @AfterEach
    void tearDown() {
        telemetry.stop();
        producer.close();
    }

    @Test
    void theListenerContinuesTheSendersTrace() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);
        String traceId = randomHex(16);
        String parentSpanId = randomHex(8);

        send(request(wallet), traceId, parentSpanId);

        eventually(WAIT, () -> assertThat(wallets.findById(wallet).orElseThrow().status())
                .isEqualTo(WalletStatus.FROZEN));
        eventually(() -> assertThat(telemetry.spansOfTrace(traceId)).anySatisfy(span -> {
            assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
            assertThat(span.getName()).contains(AccountFreezeRequestedV1.TOPIC);
            assertThat(span.getParentSpanId()).as("a child of the sender's span").isEqualTo(parentSpanId);
        }));
    }

    @Test
    void aDeadLetterKeepsTheTraceOfTheFailedRecord() {
        String traceId = randomHex(16);
        // An unknown wallet: retried, then dead-lettered.
        AccountFreezeRequestedV1 request = request(UUID.randomUUID());

        send(request, traceId, randomHex(8));

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(kafka.getBootstrapServers(), DLT)) {
            assertThat(consumer.recordsWithKey(request.walletId().toString(), 1, WAIT)).singleElement()
                    .satisfies(dead -> assertThat(header(dead, "traceparent"))
                            .matches("00-" + traceId + "-[0-9a-f]{16}-[0-9a-f]{2}"));
        }
    }

    private static AccountFreezeRequestedV1 request(UUID wallet) {
        return new AccountFreezeRequestedV1(UUID.randomUUID(), Instant.now(), wallet, UUID.randomUUID(), "R2");
    }

    /** As risk-aml-service's observed template sends it, with a sampled W3C traceparent. */
    private void send(AccountFreezeRequestedV1 request, String traceId, String parentSpanId) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(AccountFreezeRequestedV1.TOPIC,
                request.walletId().toString(), JSON.writeValueAsBytes(request));
        record.headers().add("__TypeId__", AccountFreezeRequestedV1.class.getName().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", request.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent",
                ("00-" + traceId + "-" + parentSpanId + "-01").getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send the test record", e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
