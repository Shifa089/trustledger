package com.trustledger.core.wallet;

/** Who froze a wallet: a compliance officer (FR-W-3) or the AML service (FR-AML-3, M9). */
public enum FreezeSource {
    COMPLIANCE,
    AML
}
