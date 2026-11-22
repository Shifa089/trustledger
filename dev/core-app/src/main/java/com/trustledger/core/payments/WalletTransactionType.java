package com.trustledger.core.payments;

/** The kind of a row in a wallet's transaction history (FR-S-1). */
public enum WalletTransactionType {
    /** Money added through the gateway. */
    TOPUP,
    /** A P2P transfer this wallet received (shown only once the money arrived). */
    TRANSFER_IN,
    /** A P2P transfer this wallet sent, in any status. */
    TRANSFER_OUT
}
