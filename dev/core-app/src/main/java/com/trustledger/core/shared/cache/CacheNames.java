package com.trustledger.core.shared.cache;

import java.util.Set;

/**
 * The only Redis caches that exist (ADR 0019). The cache manager is built with exactly these names and refuses any
 * other, so an {@code @Cacheable} on a new name fails instead of silently caching something new.
 * <p>
 * <b>Balances are never cached</b>: a wallet balance or a ledger-derived balance must always be read from
 * PostgreSQL in the transaction that uses it. A stale balance would let money be spent twice or hide a mismatch from
 * reconciliation. Only data that never (ledger accounts) or almost never (tier limits, which change only with a
 * redeploy) changes is cached.
 */
public final class CacheNames {

    /** Tier limits from {@code trustledger.limits.*} (wallet module), keyed by tier and a configuration fingerprint. */
    public static final String WALLET_LIMITS = "wallet-limits";

    /** Ledger account metadata by code (ledger module): ID, type, normal side, currency. Immutable rows. */
    public static final String LEDGER_ACCOUNTS = "ledger-accounts";

    public static final Set<String> ALL = Set.of(WALLET_LIMITS, LEDGER_ACCOUNTS);

    private CacheNames() {
    }
}
