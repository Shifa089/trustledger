/**
 * Reporting module (FR-S-1, M11): the read side for customers. Today: the wallet's transaction history,
 * {@code GET /wallets/me/transactions}, with filters (date range in Asia/Kolkata, type), offset pagination with a
 * whitelist of sort fields, and an optional cursor (keyset) mode for long histories; and the wallet's limit usage,
 * {@code GET /wallets/me/limit-usage} (SPEC §5 "limits used", Day 30).
 * <p>
 * Reporting owns no tables and reads no other module's schema: the caller's wallet comes from
 * {@link com.trustledger.core.wallet.WalletApi}, the rows from {@link com.trustledger.core.payments.PaymentsApi}
 * (which reads its own {@code payments.wallet_transactions} view), the caller from
 * {@link com.trustledger.core.identity.IdentityApi}. Nothing here is public to other modules.
 * <p>
 * Balances are never cached anywhere (ADR 0019); the history is always read from PostgreSQL.
 */
package com.trustledger.core.reporting;
