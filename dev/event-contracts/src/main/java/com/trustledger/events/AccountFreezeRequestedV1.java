package com.trustledger.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The AML service asks core-app to freeze a wallet (FR-AML-3): a HIGH-severity alert was raised on it. Produced by
 * risk-aml-service (M9), consumed by core-app, which freezes the wallet with source AML.
 * <p>
 * The case ID and rule code are enough for the freeze reason ("AML case ..., rule R2"); the alert's details stay in
 * the AML service.
 *
 * @param eventId    unique per event and stable across re-sends (consumers de-duplicate on it)
 * @param occurredAt when the alert was raised (UTC)
 * @param walletId   the wallet to freeze; the message key
 * @param caseId     the AML case the alert belongs to
 * @param ruleCode   the rule that fired, e.g. {@code "R2"}
 */
public record AccountFreezeRequestedV1(UUID eventId, Instant occurredAt, UUID walletId, UUID caseId,
        String ruleCode) {

    public static final String TOPIC = "trustledger.aml.account-freeze-requested.v1";

    public AccountFreezeRequestedV1 {
        Checks.required(eventId, "eventId");
        Checks.required(occurredAt, "occurredAt");
        Checks.required(walletId, "walletId");
        Checks.required(caseId, "caseId");
        Checks.notBlank(ruleCode, "ruleCode");
    }
}
