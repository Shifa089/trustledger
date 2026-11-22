/**
 * Audit module (FR-A-1, FR-A-2, M10): an append-only, hash-chained trail of every state-changing action in
 * {@code audit.audit_events}, and its read side for auditors.
 * <ul>
 *   <li><b>Domain facts</b> (Day 19): the public domain events of the other modules
 *       ({@link com.trustledger.core.kyc.KycLevelUpgraded}, {@link com.trustledger.core.wallet.WalletCreated},
 *       {@link com.trustledger.core.wallet.WalletFrozen}, {@link com.trustledger.core.wallet.WalletUnfrozen},
 *       {@link com.trustledger.core.payments.TopupCaptured}, {@link com.trustledger.core.payments.TransferCompleted},
 *       {@link com.trustledger.core.privacy.PersonalDataErased})
 *       are written <b>before the publishing transaction commits, in that transaction</b>.</li>
 *   <li><b>Officer and admin requests</b> (Day 20): endpoints marked
 *       {@link com.trustledger.core.shared.audit.Audited} are written by an aspect after the call, with the outcome
 *       (SUCCEEDED or REJECTED), the entity's state from its module's
 *       {@link com.trustledger.core.shared.audit.AuditSnapshotSource}, and the user agent.</li>
 *   <li><b>Auditors</b>: {@code GET /audit/events} (filtered, paginated) and {@code GET /audit/verify} (walks the
 *       chain, reports the first broken {@code seq}).</li>
 * </ul>
 * Writes are serialised with a Postgres advisory lock so the chain stays linear (ADR 0008).
 * <p>
 * Public API: none. Nothing calls into this module; modules talk to it only through the shared-kernel annotation
 * and SPIs.
 */
package com.trustledger.core.audit;
