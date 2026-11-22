package com.trustledger.core.wallet;

/** What {@link WalletApi#freezeForAml} did with an AML freeze request (FR-AML-3). */
public enum AmlFreezeOutcome {
    /** The wallet was ACTIVE and is now FROZEN with source AML. */
    FROZEN,
    /** Already frozen (by an officer or an earlier AML request): the existing freeze stays as it is. */
    ALREADY_FROZEN,
    /** A closed wallet can't be frozen; nothing changes. */
    WALLET_CLOSED,
    /** This event was handled before (a Kafka redelivery or an outbox re-send): nothing changes. */
    DUPLICATE
}
