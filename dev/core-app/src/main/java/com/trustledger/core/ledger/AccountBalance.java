package com.trustledger.core.ledger;

import com.trustledger.core.shared.Money;
import java.util.Currency;
import java.util.Objects;

/**
 * A ledger-derived balance on the account's normal side: credits minus debits for a liability, debits minus
 * credits for an asset. Signed, because a platform account such as suspense can legitimately go below zero.
 */
public record AccountBalance(String accountCode, Direction normalSide, long balanceMinor, Currency currency) {

    public AccountBalance {
        Objects.requireNonNull(accountCode, "accountCode");
        Objects.requireNonNull(normalSide, "normalSide");
        Objects.requireNonNull(currency, "currency");
    }

    /**
     * The balance as {@link Money}, e.g. to compare with a wallet's cached balance.
     *
     * @throws IllegalStateException if the balance is negative (for a wallet account that means a broken ledger)
     */
    public Money toMoney() {
        if (balanceMinor < 0) {
            throw new IllegalStateException("Account " + accountCode + " has a negative balance");
        }
        return Money.of(balanceMinor, currency);
    }
}
