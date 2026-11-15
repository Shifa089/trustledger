package com.trustledger.core.wallet;

/**
 * ACTIVE: money moves. FROZEN (FR-W-3): can't send, receive or hold; a hold can still be released.
 * CLOSED: nothing moves (no endpoint closes a wallet yet).
 */
public enum WalletStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
