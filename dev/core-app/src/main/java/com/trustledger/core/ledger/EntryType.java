package com.trustledger.core.ledger;

/** What kind of business event a journal entry records (SPEC §4 posting rules). */
public enum EntryType {
    TOPUP,
    P2P_TRANSFER,
    /** FR-L-3: undoes another entry; only {@link LedgerApi#reverse} posts one. */
    REVERSAL
}
