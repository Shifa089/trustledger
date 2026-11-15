package com.trustledger.core.ledger.domain;

import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * A ledger account (SPEC §4). It has no balance column: the balance is always summed from its journal lines.
 * Nothing about an account changes after it is opened, so it is {@link Immutable}.
 */
@Entity
@Immutable
@Table(name = "ledger_accounts", schema = "ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerAccount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "code", nullable = false, updatable = false, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_side", nullable = false, updatable = false, length = 6)
    private Direction normalSide;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** A new account; the normal side follows from the type (assets grow with debits, the rest with credits). */
    public static LedgerAccount open(String code, AccountType type, Currency currency, Instant now) {
        LedgerAccount account = new LedgerAccount();
        account.code = Objects.requireNonNull(code, "code");
        account.type = Objects.requireNonNull(type, "type");
        account.normalSide = type.normalSide();
        account.currency = Objects.requireNonNull(currency, "currency");
        account.createdAt = Objects.requireNonNull(now, "now");
        return account;
    }

    /**
     * The balance on the normal side, from the account's debit and credit totals.
     *
     * @throws ArithmeticException on overflow; never wraps around
     */
    public long balanceFrom(long debitsMinor, long creditsMinor) {
        return normalSide == Direction.DEBIT
                ? Math.subtractExact(debitsMinor, creditsMinor)
                : Math.subtractExact(creditsMinor, debitsMinor);
    }
}
