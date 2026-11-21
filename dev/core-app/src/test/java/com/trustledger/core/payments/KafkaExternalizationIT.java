package com.trustledger.core.payments;

import static com.trustledger.core.Eventually.eventually;
import static com.trustledger.core.KafkaTestConsumer.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.KafkaTestConsumer;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TopupSettlementService;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.events.EventExternalizationConfig;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.application.WalletComplianceService;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import com.trustledger.events.WalletFrozenV1;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox to Kafka end to end (M8, ADR 0005 part B), against a real broker (Testcontainers): each externalized
 * event arrives exactly once on its contract topic, keyed by wallet, with the contract as JSON and the
 * {@code eventId} / {@code correlationId} headers, and its outbox row is completed. Read with a plain Kafka client,
 * as risk-aml-service will.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaExternalizationIT {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    TransferService transfers;

    @Autowired
    TopupSettlementService settlement;

    @Autowired
    WalletComplianceService compliance;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    TransferCompletedRecorder recorder;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private KafkaTestConsumer consumer(String topic) {
        return new KafkaTestConsumer(kafka.getBootstrapServers(), topic);
    }

    private TransferResult send(Customer from, Customer to, String amount) {
        return transfers.transfer(new TransferCommand(from.userId(), TransferRecipient.byWalletId(to.walletId()),
                Money.parse(amount), "Kafka test"));
    }

    /** Outbox rows of one event type mentioning this ID that belong to the Kafka externalizer. */
    private List<Instant> kafkaPublicationCompletions(Class<?> eventType, UUID id) {
        return jdbc.queryForList("""
                select completion_date from shared.event_publication
                where event_type = ? and serialized_event like ? and listener_id not like ?
                """, eventType.getName(), "%" + id + "%", "%" + TransferCompletedRecorder.class.getName() + "%")
                .stream().map(row -> row.get("completion_date") == null ? null
                        : ((java.sql.Timestamp) row.get("completion_date")).toInstant())
                .toList();
    }

    @Test
    void aTransferArrivesOnceKeyedBySenderWithContractAndHeaders() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        MDC.put(CorrelationIdFilter.MDC_KEY, "it-corr-transfer");

        TransferResult result = send(sender, receiver, "250.50");

        eventually(() -> assertThat(recorder.receivedFor(result.transferId())).hasSize(1));
        TransferCompleted event = recorder.receivedFor(result.transferId()).getFirst();
        try (KafkaTestConsumer consumer = consumer(TransferCompletedV1.TOPIC)) {
            List<ConsumerRecord<String, String>> records =
                    consumer.recordsWithKey(sender.walletId().toString(), 1, WAIT);

            assertThat(records).as("exactly once").singleElement().satisfies(record -> {
                assertThat(JSON.readValue(record.value(), TransferCompletedV1.class)).isEqualTo(event.toV1());
                assertThat(header(record, EventExternalizationConfig.EVENT_ID_HEADER))
                        .isEqualTo(event.metadata().eventId().toString());
                assertThat(header(record, EventExternalizationConfig.CORRELATION_ID_HEADER))
                        .isEqualTo("it-corr-transfer");
                assertThat(header(record, "__TypeId__")).as("names the shared contract, never an internal class")
                        .isEqualTo(TransferCompletedV1.class.getName());
                assertThat(record.value()).as("internal fields stay inside")
                        .doesNotContain("journalEntryId", "senderWalletId", "metadata", "userId");
            });
        }
        eventually(() -> assertThat(kafkaPublicationCompletions(TransferCompleted.class, result.transferId()))
                .as("the Kafka publication is completed after the send").singleElement().isNotNull());
    }

    @Test
    void aRolledBackTransferSendsNothing() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            send(sender, receiver, "100.00");
            throw new IllegalStateException("Simulated failure before commit");
        })).isInstanceOf(IllegalStateException.class);

        try (KafkaTestConsumer consumer = consumer(TransferCompletedV1.TOPIC)) {
            assertThat(consumer.recordsWithKey(sender.walletId().toString(), 1, Duration.ofSeconds(3))).isEmpty();
        }
    }

    @Test
    void aCapturedTopupArrivesKeyedByWallet() {
        Customer customer = fixtures.customer(WalletTier.MIN, 0);
        UUID topupId = fixtures.insertTopup(customer.walletId(), 50_000, "PENDING", Instant.now());

        settlement.capture(new GatewayPayment(RazorpayWebhooks.newPaymentId(), fixtures.topupOrderId(topupId), Money.parse("500.00"),
                GatewayPaymentStatus.CAPTURED, null));

        try (KafkaTestConsumer consumer = consumer(TopupCapturedV1.TOPIC)) {
            assertThat(consumer.recordsWithKey(customer.walletId().toString(), 1, WAIT)).singleElement()
                    .satisfies(record -> {
                        TopupCapturedV1 contract = JSON.readValue(record.value(), TopupCapturedV1.class);
                        assertThat(contract.topupId()).isEqualTo(topupId);
                        assertThat(contract.walletId()).isEqualTo(customer.walletId());
                        assertThat(contract.amountMinor()).isEqualTo(50_000);
                        assertThat(header(record, EventExternalizationConfig.EVENT_ID_HEADER))
                                .isEqualTo(contract.eventId().toString());
                        assertThat(header(record, EventExternalizationConfig.CORRELATION_ID_HEADER))
                                .as("no request, no correlation ID").isNull();
                    });
        }
    }

    @Test
    void aFrozenWalletArrivesWithoutUserIdOrReason() {
        Customer customer = fixtures.customer(WalletTier.FULL, 0);
        String reason = "Officer note that must never leave core-app";

        compliance.freeze(customer.walletId(), UUID.randomUUID(), reason);

        try (KafkaTestConsumer consumer = consumer(WalletFrozenV1.TOPIC)) {
            assertThat(consumer.recordsWithKey(customer.walletId().toString(), 1, WAIT)).singleElement()
                    .satisfies(record -> {
                        WalletFrozenV1 contract = JSON.readValue(record.value(), WalletFrozenV1.class);
                        assertThat(contract.walletId()).isEqualTo(customer.walletId());
                        assertThat(contract.source()).isEqualTo("COMPLIANCE");
                        assertThat(record.value()).doesNotContain(customer.userId().toString(), reason);
                    });
        }
    }
}
