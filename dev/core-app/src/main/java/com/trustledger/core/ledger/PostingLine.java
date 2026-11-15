package com.trustledger.core.ledger;

import com.trustledger.core.shared.Money;
import java.util.Objects;

/**
 * One line of a posting: an account, a side and an amount. {@link Money} can't be negative, so the sign lives
 * only in {@code direction}. Whether the amount is positive, in the account's currency and part of a balanced
 * entry is checked by {@link LedgerApi#post}, not here.
 */
public record PostingLine(String accountCode, Direction direction, Money amount) {

    public PostingLine {
        Objects.requireNonNull(accountCode, "accountCode");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
    }

    public static PostingLine debit(String accountCode, Money amount) {
        return new PostingLine(accountCode, Direction.DEBIT, amount);
    }

    public static PostingLine credit(String accountCode, Money amount) {
        return new PostingLine(accountCode, Direction.CREDIT, amount);
    }
}
