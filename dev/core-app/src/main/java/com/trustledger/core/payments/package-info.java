/**
 * Payments module (FR-P-1 to FR-P-6): P2P transfers (M6) and top-ups (M7).
 * <p>
 * The transfer flow (SPEC §4 "Transfer flow"): idempotency keys ({@code @Idempotent} endpoints,
 * {@code payments.idempotency_keys}), the {@code Transfer} aggregate, recipient resolution, daily and monthly
 * limit checks, and {@code TransferService}, which locks both wallets, posts to the ledger and publishes
 * {@link com.trustledger.core.payments.TransferCompleted}.
 * <p>
 * Payments depends on identity, wallet and ledger through their public APIs only
 * ({@link com.trustledger.core.identity.IdentityApi}, {@link com.trustledger.core.wallet.WalletApi},
 * {@link com.trustledger.core.ledger.LedgerApi}). Its public API is the {@code TransferCompleted} event;
 * everything in sub-packages is internal.
 */
package com.trustledger.core.payments;
