package com.trustledger.core.privacy;

import static com.trustledger.core.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.privacy.application.AmlCaseProjectionService;
import com.trustledger.core.privacy.domain.AmlCaseProjection;
import com.trustledger.events.AmlCaseStatusChangedV1;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * core-app's copy of the AML service's open cases ({@code privacy.aml_cases}, ADR 0007): fed over Kafka, idempotent,
 * and independent of the order the events arrive in, so redeliveries and retries can't reopen a finished case.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmlCaseProjectionIT {

    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

    @Autowired
    AmlCaseProjectionService service;

    @Autowired
    AmlCaseProjection projection;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaContainer kafka;

    @Test
    void openedThenFinishedOverKafka() {
        UUID wallet = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        try (KafkaProducer<String, byte[]> producer = producer()) {
            ErasureIT.send(producer, event(wallet, caseId, "OPEN", true, T0));
            eventually(() -> assertThat(projection.hasOpenCase(wallet)).isTrue());

            ErasureIT.send(producer, event(wallet, caseId, "ESCALATED", false, T0.plusSeconds(60)));
            eventually(() -> assertThat(projection.hasOpenCase(wallet)).isFalse());
        }
        assertThat(row(caseId)).containsEntry("open", false).containsEntry("wallet_id", wallet);
    }

    @Test
    void aLateOpenedEventDoesNotReopenAFinishedCase() {
        UUID wallet = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        service.apply(caseId, wallet, false, T0.plusSeconds(60));
        service.apply(caseId, wallet, true, T0);

        assertThat(projection.hasOpenCase(wallet)).isFalse();
        assertThat(rows(wallet)).hasSize(1);
    }

    @Test
    void theSameEventTwiceChangesNothing() {
        UUID wallet = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        service.apply(caseId, wallet, true, T0);
        Map<String, Object> once = row(caseId);
        service.apply(caseId, wallet, true, T0);
        assertThat(row(caseId).get("changed_at")).isEqualTo(once.get("changed_at"));

        service.apply(caseId, wallet, false, T0.plusSeconds(60));
        Map<String, Object> finished = row(caseId);
        service.apply(caseId, wallet, false, T0.plusSeconds(60));
        assertThat(row(caseId)).isEqualTo(finished);
        assertThat(projection.hasOpenCase(wallet)).isFalse();
    }

    @Test
    void aWalletIsOpenWhileAnyOfItsCasesIs() {
        UUID wallet = UUID.randomUUID();
        UUID finished = UUID.randomUUID();
        UUID current = UUID.randomUUID();

        service.apply(finished, wallet, true, T0);
        service.apply(finished, wallet, false, T0.plusSeconds(60));
        service.apply(current, wallet, true, T0.plusSeconds(120));

        assertThat(projection.hasOpenCase(wallet)).isTrue();
        assertThat(projection.hasOpenCase(UUID.randomUUID())).isFalse();
    }

    private static AmlCaseStatusChangedV1 event(UUID wallet, UUID caseId, String status, boolean open, Instant at) {
        return new AmlCaseStatusChangedV1(UUID.randomUUID(), at, wallet, caseId, status, open);
    }

    private Map<String, Object> row(UUID caseId) {
        return jdbc.queryForMap("select * from privacy.aml_cases where case_id = ?", caseId);
    }

    private List<Map<String, Object>> rows(UUID wallet) {
        return jdbc.queryForList("select * from privacy.aml_cases where wallet_id = ?", wallet);
    }

    private KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new ByteArraySerializer());
    }
}
