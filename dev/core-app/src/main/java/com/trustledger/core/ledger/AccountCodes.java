package com.trustledger.core.ledger;

import java.util.Objects;
import java.util.UUID;

/** Ledger account codes (SPEC §4). Callers build posting lines with these, never with hand-written strings. */
public final class AccountCodes {

    /** Money captured by the payment gateway and owed to the platform (asset, seeded by V7). */
    public static final String GATEWAY_CLEARING = "PLATFORM:GATEWAY_CLEARING";

    /** Money that can't be attributed yet (liability, seeded by V7). */
    public static final String SUSPENSE = "PLATFORM:SUSPENSE";

    private static final String WALLET_PREFIX = "WALLET:";

    private AccountCodes() {
    }

    /** {@code WALLET:<walletId>}: what the platform owes the wallet's owner (liability). */
    public static String wallet(UUID walletId) {
        return WALLET_PREFIX + Objects.requireNonNull(walletId, "walletId");
    }
}
