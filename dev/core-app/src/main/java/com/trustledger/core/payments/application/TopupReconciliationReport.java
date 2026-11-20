package com.trustledger.core.payments.application;

/**
 * One run of the {@link PendingTopupReconciler}.
 *
 * @param checked      PENDING top-ups looked at
 * @param captured     credited to the wallet
 * @param parked       booked to suspense (ADR 0006)
 * @param expired      nothing captured within {@code expire-after}
 * @param stillPending nothing captured yet, still young enough to wait
 * @param skipped      the gateway was unavailable or refused the lookup; tried again next run
 * @param failed       settling threw; logged, tried again next run
 */
public record TopupReconciliationReport(int checked, int captured, int parked, int expired, int stillPending,
        int skipped, int failed) {
}
