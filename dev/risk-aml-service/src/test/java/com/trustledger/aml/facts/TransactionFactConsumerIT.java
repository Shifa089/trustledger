package com.trustledger.aml.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.trustledger.aml.Eventually;
import com.trustledger.aml.KafkaTestClient;
import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.aml.facts.application.TransactionFactRecorder;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-AML-1 consumers against real Kafka and PostgreSQL: facts are stored once per event, unreadable records go
 * straight to the dead-letter topic, and a failing record is tried 3 times before it does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionFactConsumerIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String TRANSFER_DLT = TransferCompletedV1.TOPIC + ".DLT";
    private static final String TOPUP_DLT = TopupCapturedV1.TOPIC + ".DLT";

    @Autowired
    KafkaContainer kafka;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    TransactionFactRecorder recorder;

    private KafkaTestClient client;

    @BeforeEach
    void connect() {
        client = new KafkaTestClient(kafka);
    }

    @AfterEach
    void disconnect() {
        client.close();
    }

    @Test
    void aTransferIsRecordedAsAnOutFactAndAnInFact() {
        TransferCompletedV1 event = transfer(4_600_000);

        client.sendContract(TransferCompletedV1.TOPIC, event.walletId().toString(), event, event.eventId());

        awaitFacts(event.eventId(), 2);
        List<Map<String, Object>> facts = facts(event.eventId());
        assertThat(facts).extracting(f -> f.get("direction")).containsExactlyInAnyOrder("OUT", "IN");
        Map<String, Object> out = facts.stream().filter(f -> "OUT".equals(f.get("direction"))).findFirst().orElseThrow();
        Map<String, Object> in = facts.stream().filter(f -> "IN".equals(f.get("direction"))).findFirst().orElseThrow();
        assertThat(out.get("wallet_id")).isEqualTo(event.walletId());
        assertThat(out.get("counterparty_wallet_id")).isEqualTo(event.counterpartyWalletId());
        assertThat(in.get("wallet_id")).isEqualTo(event.counterpartyWalletId());
        assertThat(in.get("counterparty_wallet_id")).isEqualTo(event.walletId());
        assertThat(facts).allSatisfy(f -> {
            assertThat(f.get("source")).isEqualTo("TRANSFER");
            assertThat(f.get("reference_id")).isEqualTo(event.transferId());
            assertThat(f.get("amount_minor")).isEqualTo(4_600_000L);
            assertThat(f.get("currency")).isEqualTo("INR");
        });
        assertThat(processedTopic(event.eventId())).isEqualTo(TransferCompletedV1.TOPIC);
    }

    @Test
    void aTopupIsRecordedAsOneInFact() {
        TopupCapturedV1 event = new TopupCapturedV1(UUID.randomUUID(), now(), UUID.randomUUID(), UUID.randomUUID(),
                50_000, "INR");

        client.sendContract(TopupCapturedV1.TOPIC, event.walletId().toString(), event, event.eventId());

        awaitFacts(event.eventId(), 1);
        assertThat(facts(event.eventId())).singleElement().satisfies(f -> {
            assertThat(f.get("direction")).isEqualTo("IN");
            assertThat(f.get("source")).isEqualTo("TOPUP");
            assertThat(f.get("wallet_id")).isEqualTo(event.walletId());
            assertThat(f.get("counterparty_wallet_id")).isNull();
            assertThat(f.get("reference_id")).isEqualTo(event.topupId());
        });
        assertThat(processedTopic(event.eventId())).isEqualTo(TopupCapturedV1.TOPIC);
    }

    @Test
    void theSameEventDeliveredTwiceIsStoredOnce() {
        TransferCompletedV1 event = transfer(100_00);
        String key = event.walletId().toString();
        // A later event on the same key lands on the same partition and is handled after both copies.
        TransferCompletedV1 marker = new TransferCompletedV1(UUID.randomUUID(), now(), event.walletId(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "INR");

        client.sendContract(TransferCompletedV1.TOPIC, key, event, event.eventId());
        client.sendContract(TransferCompletedV1.TOPIC, key, event, event.eventId());
        client.sendContract(TransferCompletedV1.TOPIC, key, marker, marker.eventId());

        awaitFacts(marker.eventId(), 2);
        assertThat(facts(event.eventId())).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM aml.processed_events WHERE event_id = ?")
                .param(event.eventId()).query(Long.class).single()).isEqualTo(1L);
        verify(recorder, timeout(5_000).times(2)).record(
                argThat((TransferCompletedV1 e) -> e != null && e.eventId().equals(event.eventId())));
    }

    @Test
    void anUnreadableRecordGoesStraightToTheDeadLetterTopic() {
        String key = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();

        client.send(TransferCompletedV1.TOPIC, key, "this is not json".getBytes(StandardCharsets.UTF_8),
                TransferCompletedV1.class.getName(), eventId);

        ConsumerRecord<String, byte[]> dead = singleDeadLetter(TRANSFER_DLT, key);
        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).as("the original bytes, unchanged")
                .isEqualTo("this is not json");
        assertThat(exceptionHeaders(dead)).contains("DeserializationException");
        assertThat(KafkaTestClient.header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TransferCompletedV1.TOPIC);
        assertThat(KafkaTestClient.header(dead, "eventId")).as("original headers kept").isEqualTo(eventId.toString());
        assertThat(processedCount(eventId)).isZero();
    }

    @Test
    void aContractThatFailsItsOwnChecksGoesToTheDeadLetterTopic() {
        String wallet = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> negative = new LinkedHashMap<>();
        negative.put("eventId", eventId.toString());
        negative.put("occurredAt", now().toString());
        negative.put("walletId", wallet);
        negative.put("topupId", UUID.randomUUID().toString());
        negative.put("amountMinor", -500);
        negative.put("currency", "INR");

        client.send(TopupCapturedV1.TOPIC, wallet, KafkaTestClient.json(negative), TopupCapturedV1.class.getName(),
                eventId);

        ConsumerRecord<String, byte[]> dead = singleDeadLetter(TOPUP_DLT, wallet);
        assertThat(exceptionHeaders(dead)).contains("DeserializationException");
        assertThat(processedCount(eventId)).isZero();
        assertThat(facts(eventId)).isEmpty();
    }

    @Test
    void aTypeOutsideTheContractsPackageIsNeverBuilt() {
        String key = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();

        client.send(TransferCompletedV1.TOPIC, key, "{}".getBytes(StandardCharsets.UTF_8), "java.util.HashMap",
                eventId);

        ConsumerRecord<String, byte[]> dead = singleDeadLetter(TRANSFER_DLT, key);
        assertThat(exceptionHeaders(dead)).contains("DeserializationException");
        assertThat(processedCount(eventId)).isZero();
    }

    @Test
    void aFailureThatClearsOnRetryIsRecordedAndNotDeadLettered() {
        TransferCompletedV1 event = transfer(200_00);
        doThrow(new TransientDataAccessResourceException("simulated database hiccup"))
                .doCallRealMethod()
                .when(recorder).record(argThat((TransferCompletedV1 e) -> e != null
                        && e.eventId().equals(event.eventId())));

        client.sendContract(TransferCompletedV1.TOPIC, event.walletId().toString(), event, event.eventId());

        awaitFacts(event.eventId(), 2);
        verify(recorder, timeout(5_000).times(2)).record(
                argThat((TransferCompletedV1 e) -> e != null && e.eventId().equals(event.eventId())));
        assertThat(client.read(TRANSFER_DLT, r -> event.walletId().toString().equals(r.key()),
                Duration.ofSeconds(3), Duration.ZERO)).isEmpty();
    }

    @Test
    void aFailureThatNeverClearsIsTriedThreeTimesThenDeadLettered() {
        TransferCompletedV1 event = transfer(300_00);
        doThrow(new TransientDataAccessResourceException("simulated outage"))
                .when(recorder).record(argThat((TransferCompletedV1 e) -> e != null
                        && e.eventId().equals(event.eventId())));

        client.sendContract(TransferCompletedV1.TOPIC, event.walletId().toString(), event, event.eventId());

        ConsumerRecord<String, byte[]> dead = singleDeadLetter(TRANSFER_DLT, event.walletId().toString());
        verify(recorder, timeout(5_000).times(3)).record(
                argThat((TransferCompletedV1 e) -> e != null && e.eventId().equals(event.eventId())));
        assertThat(exceptionHeaders(dead)).contains("TransientDataAccessResourceException");
        JsonNode payload = JsonMapper.builder().build().readTree(dead.value());
        assertThat(payload.get("eventId").asString()).as("the contract, readable for a replay")
                .isEqualTo(event.eventId().toString());
        assertThat(processedCount(event.eventId())).as("the claim rolled back with the failure").isZero();
        assertThat(facts(event.eventId())).as("nothing half-stored, so a replay from the DLT starts clean")
                .isEmpty();
    }

    private ConsumerRecord<String, byte[]> singleDeadLetter(String dlt, String key) {
        List<ConsumerRecord<String, byte[]>> dead = client.readByKey(dlt, key, WAIT, Duration.ofSeconds(2));
        assertThat(dead).as("exactly one dead letter for key %s on %s", key, dlt).hasSize(1);
        return dead.getFirst();
    }

    private static String exceptionHeaders(ConsumerRecord<?, ?> dead) {
        return KafkaTestClient.header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN) + " / "
                + KafkaTestClient.header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    private void awaitFacts(UUID eventId, int count) {
        Eventually.await(WAIT, count + " facts for event " + eventId, () -> facts(eventId).size() == count);
    }

    private List<Map<String, Object>> facts(UUID eventId) {
        return jdbc.sql("SELECT * FROM aml.transaction_facts WHERE event_id = ?").param(eventId).query().listOfRows();
    }

    private long processedCount(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM aml.processed_events WHERE event_id = ?").param(eventId)
                .query(Long.class).single();
    }

    private String processedTopic(UUID eventId) {
        return jdbc.sql("SELECT topic FROM aml.processed_events WHERE event_id = ?").param(eventId)
                .query(String.class).single();
    }

    private static TransferCompletedV1 transfer(long amountMinor) {
        return new TransferCompletedV1(UUID.randomUUID(), now(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), amountMinor, "INR");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
