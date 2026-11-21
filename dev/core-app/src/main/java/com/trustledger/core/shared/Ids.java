package com.trustledger.core.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Time-ordered UUIDv7 (RFC 9562) for rows the application inserts itself, without JPA (SPEC §6 "IDs"). JPA
 * entities get the same kind of ID from {@code @UuidGenerator(style = VERSION_7)}.
 * <p>
 * Layout: 48 bits Unix milliseconds from the injected {@link Clock}, version {@code 0111}, 12 random bits, variant
 * {@code 10}, 62 random bits. IDs sort by creation time across milliseconds, so inserts append to the primary-key
 * index instead of scattering across it. Within one millisecond the order is random, which is fine for keys.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_MILLIS = (1L << 48) - 1;

    private Ids() {
    }

    public static UUID newV7(Clock clock) {
        long millis = clock.millis();
        if (millis < 0 || millis > MAX_MILLIS) {
            throw new IllegalArgumentException("UUIDv7 needs a time between 1970 and the year 10889");
        }
        long randA = RANDOM.nextLong() & 0xFFFL;
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (0b10L << 62) | randB;
        return new UUID(msb, lsb);
    }
}
