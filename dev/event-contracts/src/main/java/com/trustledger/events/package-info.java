/**
 * Kafka event contracts shared by core-app and risk-aml-service (SPEC §6 "Events", "Kafka").
 * <p>
 * Events are immutable records with a version suffix (for example {@code TransferCompletedV1}) and a {@code TOPIC}
 * constant named {@code trustledger.<module>.<event>.v<n>}. Every contract has {@code eventId} (unique, stable
 * across re-sends, the consumers' de-duplication key), {@code occurredAt} and {@code walletId} (the message key).
 * Amounts are minor units plus an ISO currency code (ADR 0003). Contracts carry <b>no personal data</b>: IDs and
 * amounts only ({@code ContractPrivacyTest}). A breaking change means a new record ({@code ...V2}) and a new topic.
 * <p>
 * This module has no compile dependency at all, so both applications can depend on it freely. The correlation ID
 * travels in a Kafka header, not in the payload.
 */
package com.trustledger.events;
