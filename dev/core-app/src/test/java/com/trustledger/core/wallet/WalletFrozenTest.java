package com.trustledger.core.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.shared.EventMetadata;
import com.trustledger.events.WalletFrozenV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Mapping the internal event to its Kafka contract (M8). */
class WalletFrozenTest {

    private static final UUID WALLET = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID USER = UUID.fromString("0190a1b2-0000-7000-8000-0000000000aa");
    private static final UUID EVENT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2d");
    private static final Instant FROZEN_AT = Instant.parse("2026-11-21T12:00:00Z");

    @ParameterizedTest
    @EnumSource(FreezeSource.class)
    void mapsToTheContractWithoutTheUserId(FreezeSource source) {
        var event = new WalletFrozen(WALLET, USER, source, FROZEN_AT, new EventMetadata(EVENT_ID, "corr"));

        assertThat(event.toV1()).isEqualTo(new WalletFrozenV1(EVENT_ID, FROZEN_AT, WALLET, source.name()));
        assertThat(event.toV1().toString()).doesNotContain(USER.toString());
    }

    @Test
    void frozenAtIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new WalletFrozen(WALLET, USER, FreezeSource.AML, null,
                new EventMetadata(EVENT_ID, null))).withMessage("frozenAt");
    }
}
