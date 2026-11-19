package com.trustledger.core.payments.domain;

/**
 * Lifecycle of a P2P transfer (FR-P-1 to FR-P-3):
 * {@code INITIATED -> COMPLETED | HELD_FOR_REVIEW | FAILED}, {@code HELD_FOR_REVIEW -> COMPLETED | REJECTED}.
 * {@code REVERSED} is reserved for a later correction flow and is not reachable yet.
 */
public enum TransferStatus {
    INITIATED,
    HELD_FOR_REVIEW,
    COMPLETED,
    REJECTED,
    FAILED,
    REVERSED
}
