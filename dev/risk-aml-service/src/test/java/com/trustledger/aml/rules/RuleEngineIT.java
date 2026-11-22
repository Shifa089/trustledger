package com.trustledger.aml.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.aml.Eventually;
import com.trustledger.aml.KafkaTestClient;
import com.trustledger.aml.MutableClock;
import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.aml.facts.application.RecordOutcome;
import com.trustledger.aml.facts.application.TransactionFactRecorder;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * FR-AML-1/2 against real PostgreSQL (and Kafka for the end-to-end case): the windowed SQL at its edges, what each
 * rule counts, one active case per wallet, repeat suppression, duplicates and concurrent facts. The clock is fixed
 * per test, so "exactly 10 minutes ago" is exact. Every test uses fresh wallet IDs, so tests don't see each
 * other's facts.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RuleEngineIT.FixedClock.class})
class RuleEngineIT {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration MS = Duration.ofMillis(1);
    private static final long SMALL = 10_000;
    private static final long STRUCTURED = 4_600_000;
    private static final long ONE_LAKH = 10_000_000;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    TransactionFactRecorder recorder;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    KafkaContainer kafka;

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    // --- R1 velocity: more than 5 outgoing transfers within 10 minutes -------------------------------------

    @Test
    void velocityCountsATransferExactlyAtTheWindowStart() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), SMALL, NOW.minus(TEN_MINUTES));
        for (int i = 0; i < 5; i++) {
            transfer(wallet, UUID.randomUUID(), SMALL, NOW.minusSeconds(60));
        }

        assertThat(ruleCodes(wallet)).containsExactly("R1");
    }

    @Test
    void velocityIgnoresATransferOneMillisecondBeforeTheWindow() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), SMALL, NOW.minus(TEN_MINUTES).minus(MS));
        for (int i = 0; i < 5; i++) {
            transfer(wallet, UUID.randomUUID(), SMALL, NOW.minusSeconds(60));
        }

        assertThat(ruleCodes(wallet)).isEmpty();
    }

    @Test
    void velocityAlertsOncePerWindowNotOncePerTransfer() {
        UUID wallet = UUID.randomUUID();
        // The same 8 recipients throughout, so R4 fan-out (10 distinct) stays out of it.
        List<UUID> recipients = Stream.generate(UUID::randomUUID).limit(8).toList();
        for (UUID recipient : recipients) {
            transfer(wallet, recipient, SMALL, NOW);
        }
        assertThat(ruleCodes(wallet)).containsExactly("R1");

        // Past the window of the first alert, a new burst alerts again, on the same open case.
        clock.advance(TEN_MINUTES.plus(MS));
        for (UUID recipient : recipients.subList(0, 6)) {
            transfer(wallet, recipient, SMALL, clock.instant());
        }

        assertThat(ruleCodes(wallet)).containsExactly("R1", "R1");
        assertThat(activeCaseIds(wallet)).hasSize(1);
    }

    // --- R2 structuring: 3 or more transactions of 45,000.00-49,999.99 within 24 hours ---------------------

    @Test
    void structuringCountsATransactionExactlyAtTheWindowStart() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), STRUCTURED, NOW.minus(DAY));
        transfer(wallet, UUID.randomUUID(), STRUCTURED, NOW.minusSeconds(3600));
        transfer(wallet, UUID.randomUUID(), STRUCTURED, NOW);

        assertThat(alerts(wallet)).singleElement().satisfies(alert -> {
            assertThat(alert.get("rule_code")).isEqualTo("R2");
            assertThat(alert.get("severity")).isEqualTo("HIGH");
        });
    }

    @Test
    void structuringIgnoresATransactionOneMillisecondBeforeTheWindow() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), STRUCTURED, NOW.minus(DAY).minus(MS));
        transfer(wallet, UUID.randomUUID(), STRUCTURED, NOW.minusSeconds(3600));
        transfer(wallet, UUID.randomUUID(), STRUCTURED, NOW);

        assertThat(ruleCodes(wallet)).isEmpty();
    }

    @Test
    void structuringCountsOnlyWhatTheOwnerInitiatedInsideTheRange() {
        UUID wallet = UUID.randomUUID();
        // Not counted: money received from others, and amounts just outside the range.
        transfer(UUID.randomUUID(), wallet, STRUCTURED, NOW);
        transfer(UUID.randomUUID(), wallet, STRUCTURED, NOW);
        transfer(wallet, UUID.randomUUID(), 4_499_999, NOW);
        transfer(wallet, UUID.randomUUID(), 5_000_000, NOW);
        // Counted: both ends of the range, a top-up and a transfer.
        topup(wallet, 4_500_000, NOW);
        transfer(wallet, UUID.randomUUID(), 4_999_999, NOW);
        assertThat(ruleCodes(wallet)).isEmpty();

        topup(wallet, STRUCTURED, NOW);

        assertThat(ruleCodes(wallet)).containsExactly("R2");
    }

    @Test
    void threeTransfersOf46000OverKafkaAlertTheSenderOnly() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        List<TransferCompletedV1> events = List.of(event(sender, receiver, STRUCTURED, NOW.minusSeconds(120)),
                event(sender, receiver, STRUCTURED, NOW.minusSeconds(60)), event(sender, receiver, STRUCTURED, NOW));

        try (KafkaTestClient client = new KafkaTestClient(kafka)) {
            events.forEach(e -> client.sendContract(TransferCompletedV1.TOPIC, sender.toString(), e, e.eventId()));
        }

        Eventually.await(Duration.ofSeconds(30), "all three transfers recorded",
                () -> processedCount(events) == 3);
        assertThat(alerts(sender)).singleElement().satisfies(alert -> {
            assertThat(alert.get("rule_code")).isEqualTo("R2");
            assertThat(alert.get("severity")).isEqualTo("HIGH");
        });
        assertThat(ruleCodes(receiver)).isEmpty();
        assertThat(activeCaseIds(receiver)).isEmpty();
        assertThat(caseStatus(activeCaseIds(sender).getFirst())).isEqualTo("OPEN");
    }

    // --- R3 large amount: a single transaction of 1,00,000 or more -----------------------------------------

    @Test
    void largeAmountFiresAtOneLakhAndNotJustBelow() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), ONE_LAKH - 1, NOW);
        assertThat(ruleCodes(wallet)).isEmpty();

        transfer(wallet, UUID.randomUUID(), ONE_LAKH, NOW);

        assertThat(alerts(wallet)).singleElement().satisfies(alert -> {
            assertThat(alert.get("rule_code")).isEqualTo("R3");
            assertThat(alert.get("severity")).isEqualTo("MEDIUM");
            assertThat((String) alert.get("details")).endsWith("of 100000.00 (at least 100000.00)");
        });
    }

    // --- R4 fan-out: transfers to 10 or more distinct recipients within 24 hours ---------------------------

    @Test
    void fanOutCountsARecipientExactlyAtTheWindowStart() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), SMALL, NOW.minus(DAY));
        sendToNineRecipientsSpreadOverHours(wallet);

        assertThat(ruleCodes(wallet)).containsExactly("R4");
    }

    @Test
    void fanOutIgnoresARecipientOneMillisecondBeforeTheWindow() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), SMALL, NOW.minus(DAY).minus(MS));
        sendToNineRecipientsSpreadOverHours(wallet);

        assertThat(ruleCodes(wallet)).isEmpty();
    }

    @Test
    void fanOutCountsEachRecipientOnce() {
        UUID wallet = UUID.randomUUID();
        UUID regular = UUID.randomUUID();
        transfer(wallet, regular, SMALL, NOW.minus(Duration.ofHours(20)));
        transfer(wallet, regular, SMALL, NOW.minus(Duration.ofHours(19)));
        for (int i = 0; i < 8; i++) {
            transfer(wallet, UUID.randomUUID(), SMALL, NOW.minus(Duration.ofHours(i + 1)));
        }

        assertThat(ruleCodes(wallet)).as("10 transfers, 9 distinct recipients").isEmpty();
    }

    // --- Cases, duplicates, concurrency -------------------------------------------------------------------

    @Test
    void alertsJoinTheWalletsActiveCaseUntilItIsFinished() {
        UUID wallet = UUID.randomUUID();
        transfer(wallet, UUID.randomUUID(), ONE_LAKH, NOW);
        transfer(wallet, UUID.randomUUID(), ONE_LAKH, NOW);
        UUID first = activeCaseIds(wallet).getFirst();

        setStatus(first, "INVESTIGATING", null);
        transfer(wallet, UUID.randomUUID(), ONE_LAKH, NOW);
        assertThat(activeCaseIds(wallet)).containsExactly(first);

        setStatus(first, "CLOSED_FALSE_POSITIVE", "Salary payments, documents checked");
        transfer(wallet, UUID.randomUUID(), ONE_LAKH, NOW);

        UUID second = activeCaseIds(wallet).getFirst();
        assertThat(second).isNotEqualTo(first);
        assertThat(caseStatus(second)).isEqualTo("OPEN");
        assertThat(alerts(wallet)).extracting(alert -> alert.get("case_id"))
                .containsExactly(first, first, first, second);
    }

    @Test
    void aDuplicateEventRaisesNoSecondAlert() {
        UUID wallet = UUID.randomUUID();
        TransferCompletedV1 event = event(wallet, UUID.randomUUID(), ONE_LAKH, NOW);

        assertThat(recorder.record(event)).isEqualTo(RecordOutcome.RECORDED);
        assertThat(recorder.record(event)).isEqualTo(RecordOutcome.DUPLICATE);

        assertThat(ruleCodes(wallet)).containsExactly("R3");
    }

    @Test
    void concurrentTransfersFromOneWalletShareOneCaseAndAreAllCounted() throws Exception {
        UUID wallet = UUID.randomUUID();
        int transfers = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RecordOutcome>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(transfers)) {
            for (int i = 0; i < transfers; i++) {
                TransferCompletedV1 event = event(wallet, UUID.randomUUID(), ONE_LAKH, NOW);
                results.add(pool.submit(() -> {
                    start.await();
                    return recorder.record(event);
                }));
            }
            start.countDown();
            for (Future<RecordOutcome> result : results) {
                assertThat(result.get()).isEqualTo(RecordOutcome.RECORDED);
            }
        }

        // Each transaction waited for the one before it, so the last one saw all 6 transfers: R1 fired once.
        assertThat(ruleCodes(wallet)).filteredOn("R1"::equals).hasSize(1);
        assertThat(ruleCodes(wallet)).filteredOn("R3"::equals).hasSize(transfers);
        assertThat(activeCaseIds(wallet)).hasSize(1);
        assertThat(alerts(wallet)).extracting(alert -> alert.get("case_id")).containsOnly(activeCaseIds(wallet)
                .getFirst());
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private void sendToNineRecipientsSpreadOverHours(UUID wallet) {
        // An hour apart: R1 (10 minutes) never sees more than one of them.
        for (int i = 0; i < 9; i++) {
            transfer(wallet, UUID.randomUUID(), SMALL, NOW.minus(Duration.ofHours(i)));
        }
    }

    private void transfer(UUID sender, UUID receiver, long amountMinor, Instant occurredAt) {
        assertThat(recorder.record(event(sender, receiver, amountMinor, occurredAt)))
                .isEqualTo(RecordOutcome.RECORDED);
    }

    private void topup(UUID wallet, long amountMinor, Instant occurredAt) {
        assertThat(recorder.record(new TopupCapturedV1(UUID.randomUUID(), occurredAt, wallet, UUID.randomUUID(),
                amountMinor, "INR"))).isEqualTo(RecordOutcome.RECORDED);
    }

    private static TransferCompletedV1 event(UUID sender, UUID receiver, long amountMinor, Instant occurredAt) {
        return new TransferCompletedV1(UUID.randomUUID(), occurredAt, sender, receiver, UUID.randomUUID(),
                amountMinor, "INR");
    }

    private List<Map<String, Object>> alerts(UUID wallet) {
        return jdbc.sql("""
                        SELECT rule_code, severity, details, case_id FROM aml.alerts
                        WHERE wallet_id = :wallet ORDER BY created_at, id
                        """)
                .param("wallet", wallet)
                .query()
                .listOfRows();
    }

    private List<String> ruleCodes(UUID wallet) {
        return alerts(wallet).stream().map(alert -> (String) alert.get("rule_code")).toList();
    }

    private List<UUID> activeCaseIds(UUID wallet) {
        return jdbc.sql("""
                        SELECT id FROM aml.cases
                        WHERE wallet_id = :wallet AND status IN ('OPEN', 'INVESTIGATING')
                        """)
                .param("wallet", wallet)
                .query(UUID.class)
                .list();
    }

    private String caseStatus(UUID caseId) {
        return jdbc.sql("SELECT status FROM aml.cases WHERE id = :id").param("id", caseId).query(String.class)
                .single();
    }

    /** The case workflow endpoints come next session; until then the test moves the case itself. */
    private void setStatus(UUID caseId, String status, String resolution) {
        jdbc.sql("UPDATE aml.cases SET status = :status, resolution = :resolution WHERE id = :id")
                .param("status", status)
                .param("resolution", resolution)
                .param("id", caseId)
                .update();
    }

    private long processedCount(List<TransferCompletedV1> events) {
        return jdbc.sql("SELECT count(*) FROM aml.processed_events WHERE event_id IN (:ids)")
                .param("ids", events.stream().map(TransferCompletedV1::eventId).toList())
                .query(Long.class)
                .single();
    }
}
