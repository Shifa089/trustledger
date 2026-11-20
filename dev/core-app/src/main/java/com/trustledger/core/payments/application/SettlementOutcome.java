package com.trustledger.core.payments.application;

/** What {@link TopupSettlementService} did with a gateway result. */
public enum SettlementOutcome {
    /** Credited to the wallet: CAPTURED, {@code TopupCaptured} published. */
    CAPTURED,
    /** Booked to suspense: PARKED (ADR 0006). */
    PARKED,
    /** PENDING -> FAILED. */
    FAILED,
    /** PENDING -> EXPIRED. */
    EXPIRED,
    /** Nothing changed: unknown order, already settled, out of state, or a mismatch (logged). */
    IGNORED
}
