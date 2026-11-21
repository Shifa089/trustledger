package com.trustledger.aml.facts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.aml.facts.domain.Direction;
import com.trustledger.aml.facts.domain.FactSource;
import com.trustledger.aml.facts.domain.ProcessedEventRepository;
import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.facts.domain.TransactionFactRepository;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionFactRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-21T08:00:05Z");
    private static final Instant OCCURRED = Instant.parse("2026-11-21T08:00:00Z");

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final TransactionFactRepository facts = mock(TransactionFactRepository.class);
    private final List<TransactionFact> saved = new ArrayList<>();
    private final TransactionFactRecorder recorder =
            new TransactionFactRecorder(processed, facts, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void recordSaves() {
        when(facts.save(any())).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
    }

    @Test
    void aTransferGivesAnOutFactForTheSenderAndAnInFactForTheReceiver() {
        TransferCompletedV1 event = new TransferCompletedV1(UUID.randomUUID(), OCCURRED, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 4_600_000, "INR");
        when(processed.markProcessed(event.eventId(), TransferCompletedV1.TOPIC, NOW)).thenReturn(true);

        assertThat(recorder.record(event)).isEqualTo(RecordOutcome.RECORDED);

        assertThat(saved).hasSize(2);
        TransactionFact out = saved.get(0);
        TransactionFact in = saved.get(1);
        assertThat(out.getDirection()).isEqualTo(Direction.OUT);
        assertThat(out.getWalletId()).isEqualTo(event.walletId());
        assertThat(out.getCounterpartyWalletId()).isEqualTo(event.counterpartyWalletId());
        assertThat(in.getDirection()).isEqualTo(Direction.IN);
        assertThat(in.getWalletId()).isEqualTo(event.counterpartyWalletId());
        assertThat(in.getCounterpartyWalletId()).isEqualTo(event.walletId());
        assertThat(saved).allSatisfy(fact -> {
            assertThat(fact.getEventId()).isEqualTo(event.eventId());
            assertThat(fact.getReferenceId()).isEqualTo(event.transferId());
            assertThat(fact.getAmountMinor()).isEqualTo(4_600_000);
            assertThat(fact.getSource()).isEqualTo(FactSource.TRANSFER);
            assertThat(fact.getOccurredAt()).isEqualTo(OCCURRED);
            assertThat(fact.getCreatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aTopupGivesOneInFact() {
        TopupCapturedV1 event = new TopupCapturedV1(UUID.randomUUID(), OCCURRED, UUID.randomUUID(),
                UUID.randomUUID(), 50_000, "INR");
        when(processed.markProcessed(event.eventId(), TopupCapturedV1.TOPIC, NOW)).thenReturn(true);

        assertThat(recorder.record(event)).isEqualTo(RecordOutcome.RECORDED);

        assertThat(saved).singleElement().satisfies(fact -> {
            assertThat(fact.getDirection()).isEqualTo(Direction.IN);
            assertThat(fact.getSource()).isEqualTo(FactSource.TOPUP);
            assertThat(fact.getWalletId()).isEqualTo(event.walletId());
            assertThat(fact.getReferenceId()).isEqualTo(event.topupId());
            assertThat(fact.getCounterpartyWalletId()).isNull();
        });
    }

    @Test
    void anAlreadyProcessedEventStoresNothing() {
        TransferCompletedV1 transfer = new TransferCompletedV1(UUID.randomUUID(), OCCURRED, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 100, "INR");
        TopupCapturedV1 topup = new TopupCapturedV1(UUID.randomUUID(), OCCURRED, UUID.randomUUID(),
                UUID.randomUUID(), 100, "INR");
        // markProcessed answers false (the mock default): the row was already there.

        assertThat(recorder.record(transfer)).isEqualTo(RecordOutcome.DUPLICATE);
        assertThat(recorder.record(topup)).isEqualTo(RecordOutcome.DUPLICATE);

        verify(facts, never()).save(any());
    }
}
