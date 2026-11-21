package com.trustledger.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A captured top-up was credited to a wallet. Produced by core-app (payments), consumed by risk-aml-service
 * (FR-AML-1: R2 structuring, R3 large amount). Not produced for a top-up parked in suspense (ADR 0006): that money
 * never reached the wallet.
 * <p>
 * IDs and amount only: no user ID, no gateway payment details.
 *
 * @param eventId     unique per event and stable across re-sends (consumers de-duplicate on it)
 * @param occurredAt  when the top-up was captured (UTC)
 * @param walletId    the credited wallet; the message key
 * @param topupId     the top-up, as the transaction reference in an STR report
 * @param amountMinor positive amount in minor units (paise), ADR 0003
 * @param currency    ISO 4217 code, e.g. {@code INR}
 */
public record TopupCapturedV1(UUID eventId, Instant occurredAt, UUID walletId, UUID topupId, long amountMinor,
        String currency) {

    public static final String TOPIC = "trustledger.payments.topup-captured.v1";

    public TopupCapturedV1 {
        Checks.required(eventId, "eventId");
        Checks.required(occurredAt, "occurredAt");
        Checks.required(walletId, "walletId");
        Checks.required(topupId, "topupId");
        Checks.positive(amountMinor);
        Checks.currency(currency);
    }
}
