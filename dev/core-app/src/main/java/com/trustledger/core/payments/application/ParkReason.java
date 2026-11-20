package com.trustledger.core.payments.application;

/** Why a captured top-up could not be credited and was parked in suspense (ADR 0006). Logged, never shown. */
public enum ParkReason {
    /** The wallet is FROZEN or CLOSED, so it can't be credited. */
    WALLET_NOT_ACTIVE,
    /** Crediting would take the balance above the tier's maximum. */
    MAX_BALANCE,
    /** Crediting would take this month's captured top-ups above the tier's monthly load. */
    MONTHLY_LOAD
}
