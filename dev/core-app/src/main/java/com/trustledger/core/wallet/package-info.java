/**
 * Wallet module (FR-W-1 to FR-W-3): one wallet per user, created and upgraded from
 * {@link com.trustledger.core.kyc.KycLevelUpgraded}, tier limits from {@code trustledger.limits.*}, and
 * freezing by compliance officers with a history.
 * <p>
 * Public API: {@link com.trustledger.core.wallet.WalletApi} (the only way other modules read or move money in
 * a wallet), its records and enums, and the events {@link com.trustledger.core.wallet.WalletCreated},
 * {@link com.trustledger.core.wallet.WalletFrozen} and {@link com.trustledger.core.wallet.WalletUnfrozen}.
 * Everything in sub-packages is internal.
 */
package com.trustledger.core.wallet;
