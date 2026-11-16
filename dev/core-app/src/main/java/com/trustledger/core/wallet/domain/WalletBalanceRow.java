package com.trustledger.core.wallet.domain;

import java.util.Currency;
import java.util.UUID;

/**
 * The fields of a wallet that reconciliation needs, read without loading the entity.
 *
 * @param ledgerAccountId {@code null} until the wallet's {@code WALLET:<id>} account is linked
 */
public record WalletBalanceRow(UUID walletId, long balanceMinor, Currency currency, UUID ledgerAccountId) {
}
