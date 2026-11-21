package com.trustledger.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A wallet was frozen. Produced by core-app (wallet), consumed by risk-aml-service, which can see whether its own
 * freeze request ({@link AccountFreezeRequestedV1}) took effect or a compliance officer froze the wallet.
 * <p>
 * The officer's free-text reason is left out: it may quote personal data. So is the user ID.
 *
 * @param eventId    unique per event and stable across re-sends (consumers de-duplicate on it)
 * @param occurredAt when the wallet was frozen (UTC)
 * @param walletId   the frozen wallet; the message key
 * @param source     who froze it: {@code "COMPLIANCE"} or {@code "AML"}. A string, not an enum, so a consumer built
 *                   against this version still reads a value added later (tolerant reader)
 */
public record WalletFrozenV1(UUID eventId, Instant occurredAt, UUID walletId, String source) {

    public static final String TOPIC = "trustledger.wallet.wallet-frozen.v1";

    public WalletFrozenV1 {
        Checks.required(eventId, "eventId");
        Checks.required(occurredAt, "occurredAt");
        Checks.required(walletId, "walletId");
        Checks.notBlank(source, "source");
    }
}
