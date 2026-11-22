package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.domain.LedgerAccount;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * What a ledger account <em>is</em> (never what it holds): the cached value of {@link LedgerAccountDirectory}. A
 * plain record, so the JPA entity never reaches Redis. It has no balance field on purpose: balances are never cached
 * (ADR 0019).
 */
public record LedgerAccountRef(UUID id, String code, AccountType type, Direction normalSide, Currency currency) {

    public LedgerAccountRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(normalSide, "normalSide");
        Objects.requireNonNull(currency, "currency");
    }

    static LedgerAccountRef of(LedgerAccount account) {
        return new LedgerAccountRef(account.getId(), account.getCode(), account.getType(), account.getNormalSide(),
                account.getCurrency());
    }

    /** Same rule as {@link LedgerAccount#balanceFrom}: the balance on the normal side. */
    long balanceFrom(long debitsMinor, long creditsMinor) {
        return normalSide == Direction.DEBIT
                ? Math.subtractExact(debitsMinor, creditsMinor)
                : Math.subtractExact(creditsMinor, debitsMinor);
    }
}
