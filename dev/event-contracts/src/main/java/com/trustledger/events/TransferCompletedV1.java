package com.trustledger.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A P2P transfer was posted to the ledger. Produced by core-app (payments), consumed by risk-aml-service
 * (FR-AML-1: rules R1 velocity, R2 structuring, R3 large amount, R4 fan-out).
 * <p>
 * {@code walletId} is the <b>sender</b> and the Kafka message key, so one sender's transfers stay in order on one
 * partition (the rules count a sender's outgoing transfers). {@code counterpartyWalletId} is the receiver; AML
 * records the transfer for both sides from this one event.
 * <p>
 * IDs and amount only: no user ID, no names, no note.
 *
 * @param eventId              unique per event and stable across re-sends (consumers de-duplicate on it)
 * @param occurredAt           when the transfer completed (UTC)
 * @param walletId             sender wallet; the message key
 * @param counterpartyWalletId receiver wallet
 * @param transferId           the transfer, as the transaction reference in an STR report
 * @param amountMinor          positive amount in minor units (paise), ADR 0003
 * @param currency             ISO 4217 code, e.g. {@code INR}
 */
public record TransferCompletedV1(UUID eventId, Instant occurredAt, UUID walletId, UUID counterpartyWalletId,
        UUID transferId, long amountMinor, String currency) {

    public static final String TOPIC = "trustledger.payments.transfer-completed.v1";

    public TransferCompletedV1 {
        Checks.required(eventId, "eventId");
        Checks.required(occurredAt, "occurredAt");
        Checks.required(walletId, "walletId");
        Checks.required(counterpartyWalletId, "counterpartyWalletId");
        Checks.required(transferId, "transferId");
        Checks.positive(amountMinor);
        Checks.currency(currency);
        if (walletId.equals(counterpartyWalletId)) {
            throw new IllegalArgumentException("A transfer needs two different wallets");
        }
    }
}
