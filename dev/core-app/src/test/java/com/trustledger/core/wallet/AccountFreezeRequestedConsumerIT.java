package com.trustledger.core.wallet;

import static com.trustledger.core.Eventually.eventually;
import static com.trustledger.core.KafkaTestConsumer.header;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.KafkaTestConsumer;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.wallet.application.WalletComplianceService;
import com.trustledger.events.AccountFreezeRequestedV1;
import com.trustledger.events.WalletFrozenV1;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-AML-3, core-app's half, with real Kafka and PostgreSQL: an {@link AccountFreezeRequestedV1} record, shaped as
 * risk-aml-service's relay sends it, freezes the wallet with source AML, once per {@code eventId}; a record that
 * can't be read, or keeps failing, lands in the dead-letter topic. risk-aml-service's half (three ₹46,000 transfers
 * produce exactly one such record) is its {@code FreezeRequestPublishingIT}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountFreezeRequestedConsumerIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String DLT = AccountFreezeRequestedV1.TOPIC + ".DLT";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID OFFICER = UUID.randomUUID();

    @Autowired
    WalletFixtures fixtures;

    @Autowired
    WalletApi wallets;

    @Autowired
    WalletComplianceService compliance;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaContainer kafka;

    private KafkaProducer<String, byte[]> producer;

    @BeforeEach
    void producer() {
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new ByteArraySerializer());
    }

    @AfterEach
    void close() {
        producer.close();
    }

    @Test
    void aFreezeRequestFreezesTheWalletWithSourceAmlAndTellsAml() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);
        AccountFreezeRequestedV1 request = request(wallet, "R2");

        send(request, "aml-corr-1");

        eventually(WAIT, () -> assertThat(status(wallet)).isEqualTo(WalletStatus.FROZEN));
        Map<String, Object> freeze = openFreeze(wallet);
        assertThat(freeze.get("source")).isEqualTo("AML");
        assertThat(freeze.get("reason")).isEqualTo("AML case " + request.caseId() + ", rule R2");
        assertThat(freeze.get("frozen_by")).isNull();
        assertThat(processed(request.eventId())).isEqualTo(1);
        // WalletFrozen goes out like an officer's freeze (AML sees its request took effect), with AML's correlation ID.
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(kafka.getBootstrapServers(), WalletFrozenV1.TOPIC)) {
            List<ConsumerRecord<String, String>> frozen = consumer.recordsWithKey(wallet.toString(), 1, WAIT);
            assertThat(frozen).singleElement().satisfies(record -> {
                assertThat(JSON.readValue(record.value(), WalletFrozenV1.class).source()).isEqualTo("AML");
                assertThat(header(record, "correlationId")).isEqualTo("aml-corr-1");
            });
        }
    }

    @Test
    void aRedeliveredRequestIsProcessedOnce() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);
        AccountFreezeRequestedV1 request = request(wallet, "R4");
        send(request, null);
        eventually(WAIT, () -> assertThat(status(wallet)).isEqualTo(WalletStatus.FROZEN));
        // An officer reviews and unfreezes; then the outbox re-sends the same request (same eventId).
        compliance.unfreeze(wallet, OFFICER, "Reviewed: false positive");

        send(request, null);
        // A marker with the same key lands on the same partition after the duplicate; an unreadable one goes to the
        // DLT, so once it is there, the duplicate before it has been handled.
        sendRaw(unreadable(wallet.toString()));
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(kafka.getBootstrapServers(), DLT)) {
            assertThat(consumer.recordsWithKey(wallet.toString(), 1, WAIT)).hasSize(1);
        }

        assertThat(status(wallet)).as("the officer's decision stands").isEqualTo(WalletStatus.ACTIVE);
        assertThat(freezeCount(wallet)).isEqualTo(1);
        assertThat(processed(request.eventId())).isEqualTo(1);
    }

    @Test
    void aWalletAlreadyFrozenByAnOfficerKeepsThatFreeze() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);
        compliance.freeze(wallet, OFFICER, "Fraud report");
        AccountFreezeRequestedV1 request = request(wallet, "R2");

        send(request, null);

        eventually(WAIT, () -> assertThat(processed(request.eventId())).isEqualTo(1));
        assertThat(openFreeze(wallet).get("source")).isEqualTo("COMPLIANCE");
        assertThat(freezeCount(wallet)).isEqualTo(1);
    }

    @Test
    void aRecordThatCantBeReadGoesToTheDeadLetterTopic() {
        String key = UUID.randomUUID().toString();
        sendRaw(unreadable(key));

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(kafka.getBootstrapServers(), DLT)) {
            assertThat(consumer.recordsWithKey(key, 1, WAIT)).singleElement().satisfies(dead -> {
                assertThat(dead.value()).isEqualTo("{not json");
                assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(AccountFreezeRequestedV1.TOPIC);
            });
        }
    }

    @Test
    void aRequestForAnUnknownWalletIsRetriedThenDeadLetteredAndNotMarkedProcessed() {
        AccountFreezeRequestedV1 request = request(UUID.randomUUID(), "R2");

        send(request, null);

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(kafka.getBootstrapServers(), DLT)) {
            assertThat(consumer.recordsWithKey(request.walletId().toString(), 1, WAIT)).hasSize(1);
        }
        assertThat(processed(request.eventId())).isZero();
    }

    private static AccountFreezeRequestedV1 request(UUID wallet, String rule) {
        return new AccountFreezeRequestedV1(UUID.randomUUID(), Instant.now(), wallet, UUID.randomUUID(), rule);
    }

    /** As risk-aml-service's relay sends it: JSON, __TypeId__, eventId and correlationId headers, wallet as key. */
    private void send(AccountFreezeRequestedV1 request, String correlationId) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(AccountFreezeRequestedV1.TOPIC,
                request.walletId().toString(), JSON.writeValueAsBytes(request));
        record.headers().add("__TypeId__", AccountFreezeRequestedV1.class.getName().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", request.eventId().toString().getBytes(StandardCharsets.UTF_8));
        if (correlationId != null) {
            record.headers().add("correlationId", correlationId.getBytes(StandardCharsets.UTF_8));
        }
        sendRaw(record);
    }

    private void sendRaw(ProducerRecord<String, byte[]> record) {
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send the test record", e);
        }
    }

    private WalletStatus status(UUID wallet) {
        return wallets.findById(wallet).orElseThrow().status();
    }

    private Map<String, Object> openFreeze(UUID wallet) {
        return jdbc.sql("SELECT * FROM wallet.wallet_freezes WHERE wallet_id = :w AND unfrozen_at IS NULL")
                .param("w", wallet).query().singleRow();
    }

    private long freezeCount(UUID wallet) {
        return jdbc.sql("SELECT count(*) FROM wallet.wallet_freezes WHERE wallet_id = :w")
                .param("w", wallet).query(Long.class).single();
    }

    private long processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM wallet.processed_events WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single();
    }

    private static ProducerRecord<String, byte[]> unreadable(String key) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(AccountFreezeRequestedV1.TOPIC, key,
                "{not json".getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__", AccountFreezeRequestedV1.class.getName().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
