package com.trustledger.aml.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.aml.Eventually;
import com.trustledger.aml.KafkaTestClient;
import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.aml.cases.application.FreezeRequestRelay;
import com.trustledger.aml.facts.application.TransactionFactRecorder;
import com.trustledger.events.AccountFreezeRequestedV1;
import com.trustledger.events.TransferCompletedV1;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-AML-3 end to end inside the AML service, with real PostgreSQL and Kafka: transfer events in, one
 * {@link AccountFreezeRequestedV1} out, through the outbox ({@code aml.freeze_requests}) and the scheduled relay.
 * core-app's side (the freeze) is {@code AccountFreezeRequestedConsumerIT} there.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FreezeRequestPublishingIT {

    private static final long STRUCTURED = 4_600_000; // Rs 46,000.00, inside R2's range
    private static final long ONE_LAKH = 10_000_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SETTLE = Duration.ofSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    TransactionFactRecorder recorder;

    @Autowired
    FreezeRequestRelay relay;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaContainer kafka;

    @Test
    void threeTransfersOf46000OverKafkaPublishExactlyOneFreezeRequestForTheSender() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Instant now = Instant.now();
        UUID lastEventId = null;
        try (KafkaTestClient client = new KafkaTestClient(kafka)) {
            for (int i = 0; i < 3; i++) {
                lastEventId = UUID.randomUUID();
                client.sendContract(TransferCompletedV1.TOPIC, sender.toString(), new TransferCompletedV1(lastEventId,
                        now.minusSeconds(60L * (3 - i)), sender, receiver, UUID.randomUUID(), STRUCTURED, "INR"),
                        lastEventId);
            }

            List<ConsumerRecord<String, byte[]>> records = client.readByKey(AccountFreezeRequestedV1.TOPIC,
                    sender.toString(), TIMEOUT, SETTLE);

            assertThat(records).singleElement().satisfies(record -> {
                AccountFreezeRequestedV1 event = JSON.readValue(record.value(), AccountFreezeRequestedV1.class);
                Map<String, Object> request = freezeRequest(sender);
                assertThat(event.walletId()).isEqualTo(sender);
                assertThat(event.ruleCode()).isEqualTo("R2");
                assertThat(event.eventId()).isEqualTo(request.get("id"));
                assertThat(event.caseId()).isEqualTo(request.get("case_id"));
                assertThat(KafkaTestClient.header(record, "eventId")).isEqualTo(event.eventId().toString());
                assertThat(KafkaTestClient.header(record, KafkaTestClient.TYPE_ID_HEADER))
                        .isEqualTo(AccountFreezeRequestedV1.class.getName());
                // The correlation ID of the record that tipped the rule, so the freeze can be traced to it.
                assertThat(KafkaTestClient.header(record, "correlationId")).isNotBlank();
            });
        }
        assertThat(freezeRequest(sender).get("published_at")).isNotNull();
        // The receiver got the same money but did nothing: no case, no freeze (SPEC §2 "How the rules count").
        assertThat(count("SELECT count(*) FROM aml.freeze_requests WHERE wallet_id = :w", receiver)).isZero();
    }

    @Test
    void aMediumAlertPublishesNoFreezeRequest() {
        UUID sender = UUID.randomUUID();
        recorder.record(new TransferCompletedV1(UUID.randomUUID(), Instant.now(), sender, UUID.randomUUID(),
                UUID.randomUUID(), ONE_LAKH, "INR"));

        assertThat(count("SELECT count(*) FROM aml.alerts WHERE wallet_id = :w AND rule_code = 'R3'", sender))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM aml.freeze_requests WHERE wallet_id = :w", sender)).isZero();
        try (KafkaTestClient client = new KafkaTestClient(kafka)) {
            assertThat(client.readByKey(AccountFreezeRequestedV1.TOPIC, sender.toString(), Duration.ofSeconds(3),
                    Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void aPublishedRequestIsNotSentAgain() {
        UUID sender = UUID.randomUUID();
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            recorder.record(new TransferCompletedV1(UUID.randomUUID(), now.minusSeconds(i), sender,
                    UUID.randomUUID(), UUID.randomUUID(), STRUCTURED, "INR"));
        }
        Eventually.await(TIMEOUT, "freeze request published",
                () -> freezeRequest(sender).get("published_at") != null);

        relay.relayPending();
        relay.relayPending();

        try (KafkaTestClient client = new KafkaTestClient(kafka)) {
            assertThat(client.readByKey(AccountFreezeRequestedV1.TOPIC, sender.toString(), TIMEOUT, SETTLE))
                    .hasSize(1);
        }
        assertThat(freezeRequest(sender).get("attempts")).isEqualTo(1);
    }

    private Map<String, Object> freezeRequest(UUID wallet) {
        return jdbc.sql("SELECT * FROM aml.freeze_requests WHERE wallet_id = :w")
                .param("w", wallet)
                .query()
                .singleRow();
    }

    private long count(String sql, UUID wallet) {
        return jdbc.sql(sql).param("w", wallet).query(Long.class).single();
    }
}
