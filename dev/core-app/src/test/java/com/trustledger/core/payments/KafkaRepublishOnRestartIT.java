package com.trustledger.core.payments;

import static com.trustledger.core.Eventually.eventually;
import static com.trustledger.core.KafkaTestConsumer.header;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.KafkaTestConsumer;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.events.EventExternalizationConfig;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.events.TransferCompletedV1;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.support.PersistentApplicationEventMulticaster;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka is down when a transfer commits (M8, ADR 0005 part B): the money moves anyway, the outbox row stays
 * incomplete, and the restart sends it with the <b>same</b> {@code eventId}, so consumers can de-duplicate.
 * <p>
 * The broker is paused (its own context: short producer timeouts, so the failed send is quick). The restart is the
 * hook Modulith runs at startup, {@link PersistentApplicationEventMulticaster#afterSingletonsInstantiated()}, which
 * reads {@code spring.modulith.events.republish-outstanding-events-on-restart} (true in application.yml): calling it
 * again does what a restarted instance does first, without paying for a second application.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.kafka.producer.properties[max.block.ms]=3000",
        "spring.kafka.producer.properties[request.timeout.ms]=2000",
        "spring.kafka.producer.properties[delivery.timeout.ms]=4000"})
class KafkaRepublishOnRestartIT {

    @Autowired
    TransferService transfers;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    TransferCompletedRecorder recorder;

    @Autowired
    PersistentApplicationEventMulticaster multicaster;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    JdbcTemplate jdbc;

    /** The Kafka externalizer's outbox row of this transfer. */
    private Map<String, Object> kafkaPublicationOf(UUID transferId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, completion_date from shared.event_publication
                where event_type = ? and serialized_event like ? and listener_id not like ? and listener_id not like ?
                """, TransferCompleted.class.getName(), "%" + transferId + "%",
                "%" + TransferCompletedRecorder.class.getName() + "%", "%.notification.%");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private void pauseKafka() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpauseKafka() {
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    @Test
    void anEventThatCouldNotBeSentIsSentOnRestartWithTheSameEventId() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        TransferResult result;
        pauseKafka();
        try {
            result = transfers.transfer(new TransferCommand(sender.userId(),
                    TransferRecipient.byWalletId(receiver.walletId()), Money.parse("120.00"), "Kafka down"));

            // The money moved; only the send failed.
            assertThat(fixtures.transfersFrom(sender.walletId())).isEqualTo(1);
            eventually(Duration.ofSeconds(30), () -> assertThat(kafkaPublicationOf(result.transferId()))
                    .containsEntry("status", "FAILED").containsEntry("completion_date", null));
        } finally {
            unpauseKafka();
        }
        TransferCompleted event = recorder.receivedFor(result.transferId()).getFirst();

        multicaster.afterSingletonsInstantiated(); // what a restarted instance does first

        try (KafkaTestConsumer consumer = new KafkaTestConsumer(kafka.getBootstrapServers(),
                TransferCompletedV1.TOPIC)) {
            assertThat(consumer.recordsWithKey(sender.walletId().toString(), 1, Duration.ofSeconds(30)))
                    .singleElement().satisfies(record -> {
                        assertThat(header(record, EventExternalizationConfig.EVENT_ID_HEADER))
                                .as("same identity as the original publication")
                                .isEqualTo(event.metadata().eventId().toString());
                        assertThat(JsonMapper.builder().build().readValue(record.value(), TransferCompletedV1.class))
                                .isEqualTo(event.toV1());
                    });
        }
        eventually(() -> assertThat(kafkaPublicationOf(result.transferId()).get("completion_date")).isNotNull());
    }
}
