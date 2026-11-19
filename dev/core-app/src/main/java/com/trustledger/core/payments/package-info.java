/**
 * Payments module (FR-P-1 to FR-P-6): P2P transfers (M6) and top-ups (M7).
 * <p>
 * M6 part 1 provides the building blocks of the transfer flow (SPEC §4 "Transfer flow"): idempotency keys
 * ({@code @Idempotent} endpoints, {@code payments.idempotency_keys}), the {@code Transfer} aggregate, recipient
 * resolution through {@link com.trustledger.core.identity.IdentityApi} and
 * {@link com.trustledger.core.wallet.WalletApi}, and the daily and monthly limit checks.
 * <p>
 * Payments depends on identity, wallet and (from part 2) ledger through their public APIs only. It exposes no
 * public API yet; everything in sub-packages is internal.
 */
package com.trustledger.core.payments;
