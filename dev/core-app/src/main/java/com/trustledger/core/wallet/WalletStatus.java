package com.trustledger.core.wallet;

/**
 * ACTIVE: money moves. FROZEN (FR-W-3): can't send, receive or hold; a hold can still be released.
 * CLOSED: nothing moves. Only the owner's erasure closes a wallet, and only an empty one (FR-PR-2).
 */
public enum WalletStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
