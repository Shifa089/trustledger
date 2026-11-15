/**
 * KYC module (FR-KYC-1 to FR-KYC-4): minimum KYC with automatic approval, full KYC with documents in S3
 * (behind the {@code DocumentStorage} port, ADR 0011), the KYC case state machine with maker-checker approval
 * by compliance officers, and the {@link com.trustledger.core.kyc.KycLevelUpgraded} event.
 * <p>
 * Public API: {@link com.trustledger.core.kyc.KycLevel} and {@link com.trustledger.core.kyc.KycLevelUpgraded}.
 * Everything in sub-packages is internal.
 */
package com.trustledger.core.kyc;
