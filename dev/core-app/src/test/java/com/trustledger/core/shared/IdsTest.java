package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Day 14 B, finding L4: application-generated row IDs are time-ordered UUIDv7 (RFC 9562), like the entities'. */
class IdsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:15:30.123Z");

    @Test
    void isVersion7WithTheRfcVariant() {
        UUID id = Ids.newV7(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // IETF variant, binary 10
    }

    @Test
    void firstFortyEightBitsAreTheClockMillis() {
        UUID id = Ids.newV7(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
        // Independently: the first 12 hex digits are the millis in hex.
        assertThat(id.toString().replace("-", "").substring(0, 12))
                .isEqualTo(String.format("%012x", NOW.toEpochMilli()));
    }

    @Test
    void laterMillisSortAfterEarlierOnes() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(Ids.newV7(Clock.fixed(NOW.plusMillis(i), ZoneOffset.UTC)));
        }
        // String order is what Postgres' uuid ordering and the B-tree index follow.
        List<String> asText = ids.stream().map(UUID::toString).toList();
        assertThat(asText).isSorted();
    }

    @Test
    void idsInTheSameMillisecondAreStillUnique() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        HashSet<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newV7(fixed));
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void refusesTimesOutsideFortyEightBits() {
        assertThatThrownBy(() -> Ids.newV7(Clock.fixed(Instant.EPOCH.minusMillis(1), ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ids.newV7(Clock.fixed(Instant.ofEpochMilli(1L << 48), ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
