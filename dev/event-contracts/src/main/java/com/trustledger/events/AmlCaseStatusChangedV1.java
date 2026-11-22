package com.trustledger.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An AML case was opened or finished (FR-AML-4). Produced by risk-aml-service from its outbox, consumed by core-app,
 * which keeps a local list of open cases per wallet so erasure (FR-PR-2) can be refused while one is open, without a
 * call to the AML service.
 * <p>
 * Only the case lifecycle is sent: no alert details, no officer notes (they may quote personal data). Assignment
 * (OPEN → INVESTIGATING) is not sent: the case stays open either way.
 *
 * @param eventId    unique per event and stable across re-sends (consumers de-duplicate on it)
 * @param occurredAt when the case changed (UTC)
 * @param walletId   the case's wallet; the message key, so one wallet's events stay in order
 * @param caseId     the AML case
 * @param status     {@code "OPEN"}, {@code "CLOSED_FALSE_POSITIVE"} or {@code "ESCALATED"}. A string, not an enum, so
 *                   a consumer built against this version still reads a value added later (tolerant reader)
 * @param open       whether the case is still open (OPEN or INVESTIGATING) after this change; consumers decide on this
 *                   flag, not on the status name
 */
public record AmlCaseStatusChangedV1(UUID eventId, Instant occurredAt, UUID walletId, UUID caseId, String status,
        boolean open) {

    public static final String TOPIC = "trustledger.aml.case-status-changed.v1";

    public AmlCaseStatusChangedV1 {
        Checks.required(eventId, "eventId");
        Checks.required(occurredAt, "occurredAt");
        Checks.required(walletId, "walletId");
        Checks.required(caseId, "caseId");
        Checks.notBlank(status, "status");
    }
}
