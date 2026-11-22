package com.trustledger.core.audit.domain;

/** What happened, as stored in {@code audit_events.action}. One per audited domain event today (Day 19). */
public enum AuditAction {

    KYC_LEVEL_UPGRADED,
    WALLET_CREATED,
    WALLET_FROZEN,
    WALLET_UNFROZEN,
    TOPUP_CAPTURED,
    TRANSFER_COMPLETED
}
