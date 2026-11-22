package com.trustledger.core.audit.domain;

/**
 * What happened, as stored in {@code audit_events.action}.
 * <ul>
 *   <li>Domain facts, written in the business transaction by the event listeners (Day 19): past tense.</li>
 *   <li>Officer and admin requests, written by the {@code @Audited} aspect around the endpoint (Day 20): the
 *       command, with its outcome (SUCCEEDED or REJECTED) in {@code after}.</li>
 * </ul>
 */
public enum AuditAction {

    KYC_LEVEL_UPGRADED,
    WALLET_CREATED,
    WALLET_FROZEN,
    WALLET_UNFROZEN,
    TOPUP_CAPTURED,
    TRANSFER_COMPLETED,

    KYC_CASE_VERIFY,
    KYC_CASE_APPROVE,
    KYC_CASE_REJECT,
    KYC_DOCUMENT_VIEW,
    WALLET_FREEZE,
    WALLET_UNFREEZE,
    TRANSFER_APPROVE,
    TRANSFER_REJECT,
    RECONCILIATION_RUN
}
