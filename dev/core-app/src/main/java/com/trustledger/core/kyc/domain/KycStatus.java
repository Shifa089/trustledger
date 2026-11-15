package com.trustledger.core.kyc.domain;

/**
 * KYC case states (SPEC §4). Allowed moves, all inside {@link KycCase}:
 * <pre>
 * MIN:  SUBMITTED -> APPROVED                      (system, after format checks)
 * FULL: SUBMITTED -> VERIFIED -> APPROVED          (officer A, then a different officer B)
 *       SUBMITTED | VERIFIED -> REJECTED           (either officer, with a reason)
 * </pre>
 * APPROVED and REJECTED are final.
 */
public enum KycStatus {
    SUBMITTED,
    VERIFIED,
    APPROVED,
    REJECTED;

    public boolean isFinal() {
        return this == APPROVED || this == REJECTED;
    }
}
