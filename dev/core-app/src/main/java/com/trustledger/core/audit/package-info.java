/**
 * Audit module (FR-A-1, M10): an append-only, hash-chained trail of every state-changing action in
 * {@code audit.audit_events}.
 * <p>
 * It listens to the public domain events of the other modules ({@link com.trustledger.core.kyc.KycLevelUpgraded},
 * {@link com.trustledger.core.wallet.WalletCreated}, {@link com.trustledger.core.wallet.WalletFrozen},
 * {@link com.trustledger.core.wallet.WalletUnfrozen}, {@link com.trustledger.core.payments.TopupCaptured},
 * {@link com.trustledger.core.payments.TransferCompleted}) <b>before the publishing transaction commits, in that
 * transaction</b>: the audit row and the business change commit or roll back together (ADR 0008). Writes are
 * serialised with a Postgres advisory lock so the chain stays linear.
 * <p>
 * Public API: none. Nothing calls into this module; it depends on other modules, never the other way round.
 */
package com.trustledger.core.audit;
