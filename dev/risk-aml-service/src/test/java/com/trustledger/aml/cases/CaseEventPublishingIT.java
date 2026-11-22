package com.trustledger.aml.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.aml.KafkaTestClient;
import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.aml.cases.application.AmlCaseService;
import com.trustledger.aml.facts.application.TransactionFactRecorder;
import com.trustledger.events.AmlCaseStatusChangedV1;
import com.trustledger.events.TransferCompletedV1;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * FR-PR-2 (the AML side): opening and finishing a case reach Kafka as {@link AmlCaseStatusChangedV1} through the outbox
 * ({@code aml.case_events}) and the scheduled relay; core-app's side is {@code AmlCaseProjectionIT} there. Assignment
 * sends nothing, and the officer's notes are never part of the message.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CaseEventPublishingIT {

    private static final long ONE_LAKH = 10_000_000; // R3, MEDIUM: opens a case, requests no freeze
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SETTLE = Duration.ofSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    TransactionFactRecorder recorder;

    @Autowired
    AmlCaseService cases;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaContainer kafka;

    @Test
    void anOpenedThenClosedCaseIsPublishedAsOpenThenNotOpen() {
        UUID wallet = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        recorder.record(new TransferCompletedV1(UUID.randomUUID(), Instant.now(), wallet, UUID.randomUUID(),
                UUID.randomUUID(), ONE_LAKH, "INR"));
        UUID caseId = jdbc.sql("SELECT id FROM aml.cases WHERE wallet_id = :w").param("w", wallet)
                .query(UUID.class).single();

        cases.assign(caseId, officer, null);
        cases.closeAsFalsePositive(caseId, officer, "Known employer bonus; documents seen.");

        try (KafkaTestClient client = new KafkaTestClient(kafka)) {
            List<ConsumerRecord<String, byte[]>> records = client.readByKey(AmlCaseStatusChangedV1.TOPIC,
                    wallet.toString(), TIMEOUT, SETTLE);

            assertThat(records).hasSize(2);
            AmlCaseStatusChangedV1 opened = JSON.readValue(records.get(0).value(), AmlCaseStatusChangedV1.class);
            AmlCaseStatusChangedV1 closed = JSON.readValue(records.get(1).value(), AmlCaseStatusChangedV1.class);
            assertThat(opened.caseId()).isEqualTo(caseId);
            assertThat(opened.status()).isEqualTo("OPEN");
            assertThat(opened.open()).isTrue();
            assertThat(closed.caseId()).isEqualTo(caseId);
            assertThat(closed.status()).isEqualTo("CLOSED_FALSE_POSITIVE");
            assertThat(closed.open()).isFalse();
            assertThat(new String(records.get(1).value())).doesNotContain("employer");
            assertThat(KafkaTestClient.header(records.get(0), "eventId")).isEqualTo(opened.eventId().toString());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM aml.case_events WHERE case_id = :c AND published_at IS NOT NULL")
                .param("c", caseId).query(Long.class).single()).isEqualTo(2);
    }
}
