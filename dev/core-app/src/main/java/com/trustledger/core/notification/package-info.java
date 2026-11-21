/**
 * Notification module (FR-N-1, M8): plain-text e-mails for a completed transfer (to the sender and the receiver), a
 * captured top-up, a KYC upgrade and a frozen wallet. It only reacts to other modules' events
 * ({@link com.trustledger.core.payments.TransferCompleted}, {@link com.trustledger.core.payments.TopupCaptured},
 * {@link com.trustledger.core.kyc.KycLevelUpgraded}, {@link com.trustledger.core.wallet.WalletFrozen}) through
 * {@code @ApplicationModuleListener}, so it runs after the business transaction has committed and can never roll
 * it back. Recipients come from {@link com.trustledger.core.identity.IdentityApi#findNotificationContact}.
 * <p>
 * Every attempt is recorded in {@code notification.notifications} (SENT, FAILED or SKIPPED), without personal data.
 * <p>
 * Public API: none. Nothing calls into this module.
 */
package com.trustledger.core.notification;
