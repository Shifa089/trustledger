package com.trustledger.aml.facts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionFactTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECEIVER = UUID.randomUUID();
    private static final UUID REFERENCE = UUID.randomUUID();
    private static final Instant OCCURRED = Instant.parse("2026-11-21T08:00:00Z");
    private static final Instant NOW = OCCURRED.plusSeconds(2);

    @Test
    void outgoingTransferIsTheSenderSide() {
        TransactionFact fact = TransactionFact.outgoingTransfer(EVENT, SENDER, RECEIVER, REFERENCE, 4_600_000, "INR",
                OCCURRED, NOW);

        assertThat(fact.getWalletId()).isEqualTo(SENDER);
        assertThat(fact.getCounterpartyWalletId()).isEqualTo(RECEIVER);
        assertThat(fact.getDirection()).isEqualTo(Direction.OUT);
        assertThat(fact.getSource()).isEqualTo(FactSource.TRANSFER);
        assertThat(fact.getReferenceId()).isEqualTo(REFERENCE);
        assertThat(fact.getAmountMinor()).isEqualTo(4_600_000);
        assertThat(fact.getOccurredAt()).isEqualTo(OCCURRED);
        assertThat(fact.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void incomingTransferIsTheReceiverSide() {
        TransactionFact fact = TransactionFact.incomingTransfer(EVENT, RECEIVER, SENDER, REFERENCE, 100, "INR",
                OCCURRED, NOW);

        assertThat(fact.getWalletId()).isEqualTo(RECEIVER);
        assertThat(fact.getCounterpartyWalletId()).isEqualTo(SENDER);
        assertThat(fact.getDirection()).isEqualTo(Direction.IN);
    }

    @Test
    void topupIsIncomingWithoutCounterparty() {
        TransactionFact fact = TransactionFact.topup(EVENT, RECEIVER, REFERENCE, 50_000, "INR", OCCURRED, NOW);

        assertThat(fact.getDirection()).isEqualTo(Direction.IN);
        assertThat(fact.getSource()).isEqualTo(FactSource.TOPUP);
        assertThat(fact.getCounterpartyWalletId()).isNull();
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> TransactionFact.topup(EVENT, RECEIVER, REFERENCE, 0, "INR", OCCURRED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionFact.outgoingTransfer(EVENT, SENDER, SENDER, REFERENCE, 1, "INR",
                OCCURRED, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionFact.outgoingTransfer(EVENT, SENDER, null, REFERENCE, 1, "INR",
                OCCURRED, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionFact.topup(null, RECEIVER, REFERENCE, 1, "INR", OCCURRED, NOW))
                .isInstanceOf(NullPointerException.class);
    }
}
