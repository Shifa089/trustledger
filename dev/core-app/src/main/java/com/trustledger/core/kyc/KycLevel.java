package com.trustledger.core.kyc;

/** KYC levels (SPEC §4). MIN: format-checked details, auto-approved. FULL: documents plus maker-checker. */
public enum KycLevel {
    MIN,
    FULL
}
