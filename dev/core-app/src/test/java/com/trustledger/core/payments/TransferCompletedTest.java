package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.events.TransferCompletedV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mapping the internal event to its Kafka contract (M8). */
class TransferCompletedTest {

    private static final UUID TRANSFER = UUID.fromString("0190a1b2-0000-7000-8000-000000000001");
    private static final UUID SENDER = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID RECEIVER = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");
    private static final UUID ENTRY = UUID.fromString("0190a1b2-0000-7000-8000-0000000000e1");
    private static final UUID EVENT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
    private static final Instant COMPLETED_AT = Instant.parse("2026-11-21T10:15:30Z");

    private final TransferCompleted event = new TransferCompleted(TRANSFER, SENDER, RECEIVER, Money.parse("46000.50"),
            ENTRY, COMPLETED_AT, new EventMetadata(EVENT_ID, "corr-1"));

    @Test
    void mapsToTheContractWithTheSenderAsWalletId() {
        assertThat(event.toV1()).isEqualTo(new TransferCompletedV1(EVENT_ID, COMPLETED_AT, SENDER, RECEIVER, TRANSFER,
                4_600_050, "INR"));
    }

    @Test
    void mappingIsStableSoAResubmittedEventKeepsItsEventId() {
        assertThat(event.toV1()).isEqualTo(event.toV1());
        assertThat(event.toV1().eventId()).isEqualTo(event.metadata().eventId());
    }

    @Test
    void metadataIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new TransferCompleted(TRANSFER, SENDER, RECEIVER,
                Money.parse("1.00"), ENTRY, COMPLETED_AT, null)).withMessage("metadata");
    }
}
