/**
 * Ledger module (FR-L-1 to FR-L-4): double-entry accounts, journal entries and lines, reversals and
 * ledger-derived balances. The ledger is the source of truth for money; wallet balances are a cache of it.
 * <p>
 * Public API: {@link com.trustledger.core.ledger.LedgerApi} and its records and enums. The ledger depends on
 * no other business module (CLAUDE.md): the wallet module opens a wallet's account through
 * {@link com.trustledger.core.ledger.LedgerApi#openWalletAccount}, not the other way round. Everything in
 * sub-packages is internal.
 */
package com.trustledger.core.ledger;
