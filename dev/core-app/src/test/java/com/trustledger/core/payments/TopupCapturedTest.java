package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.events.TopupCapturedV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mapping the internal event to its Kafka contract (M8). */
class TopupCapturedTest {

    private static final UUID TOPUP = UUID.fromString("0190a1b2-0000-7000-8000-000000000002");
    private static final UUID WALLET = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID ENTRY = UUID.fromString("0190a1b2-0000-7000-8000-0000000000e2");
    private static final UUID EVENT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2c");
    private static final Instant CAPTURED_AT = Instant.parse("2026-11-21T11:00:00Z");

    private final TopupCaptured event = new TopupCaptured(TOPUP, WALLET, Money.parse("100000.00"), ENTRY, CAPTURED_AT,
            new EventMetadata(EVENT_ID, null));

    @Test
    void mapsToTheContractInMinorUnits() {
        assertThat(event.toV1()).isEqualTo(new TopupCapturedV1(EVENT_ID, CAPTURED_AT, WALLET, TOPUP, 10_000_000,
                "INR"));
    }

    @Test
    void mappingIsStableSoAResubmittedEventKeepsItsEventId() {
        assertThat(event.toV1()).isEqualTo(event.toV1());
    }

    @Test
    void metadataIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new TopupCaptured(TOPUP, WALLET, Money.parse("1.00"), ENTRY,
                CAPTURED_AT, null)).withMessage("metadata");
    }
}
