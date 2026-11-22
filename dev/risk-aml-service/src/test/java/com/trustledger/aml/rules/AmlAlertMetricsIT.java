package com.trustledger.aml.rules;

import static com.trustledger.aml.Eventually.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.aml.KafkaTestClient;
import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.aml.rules.application.AmlAlertMetrics;
import com.trustledger.events.TransferCompletedV1;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * M12 end to end: a ₹1,00,000 {@code TransferCompletedV1} on Kafka raises R3 (large amount) alerts, and
 * {@code trustledger.aml.alerts{rule=R3}} goes up by (at least) the number of R3 alerts saved. The counter is shared
 * by the whole context, so the test compares before and after.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmlAlertMetricsIT {

    private static final long ONE_LAKH = 10_000_000;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaContainer kafka;

    @Test
    void aLargeTransferFromKafkaIsCountedUnderItsRule() {
        double before = r3Count();
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        TransferCompletedV1 event = new TransferCompletedV1(UUID.randomUUID(), Instant.now(), sender, receiver,
                UUID.randomUUID(), ONE_LAKH, "INR");

        try (KafkaTestClient client = new KafkaTestClient(kafka)) {
            client.sendContract(TransferCompletedV1.TOPIC, sender.toString(), event, event.eventId());
        }

        await(Duration.ofSeconds(20), "R3 alerts saved and counted", () -> {
            long saved = r3Alerts(sender, receiver);
            return saved > 0 && r3Count() - before >= saved; // >=: another class's late Kafka record may add R3s too
        });
        assertThat(meters.get(AmlAlertMetrics.ALERTS).tag("rule", "R3").counter().getId().getTag("severity"))
                .isEqualTo("MEDIUM");
    }

    private double r3Count() {
        return meters.get(AmlAlertMetrics.ALERTS).tag("rule", "R3").counter().count();
    }

    private long r3Alerts(UUID sender, UUID receiver) {
        return jdbc.sql("select count(*) from aml.alerts where rule_code = 'R3' and wallet_id in (?, ?)")
                .params(sender, receiver).query(Long.class).single();
    }
}
