package com.trustledger.core.shared;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A non-negative amount of money in minor units (paise for INR).
 * <p>
 * Never uses floating point. Invariant violations (negative amount, currency mismatch, result below
 * zero) are programming errors and throw {@link IllegalArgumentException}; arithmetic overflow throws
 * {@link ArithmeticException}. Only {@link #parse} handles untrusted input, and it throws
 * {@link TrustLedgerException} with {@link ErrorCode#VALIDATION_FAILED}.
 */
public record Money(long amountMinor, Currency currency) implements Comparable<Money> {

    public static final Currency INR = Currency.getInstance("INR");

    private static final int MINOR_DIGITS = 2;
    private static final Pattern DECIMAL = Pattern.compile("\\d+(\\.\\d{1,2})?");

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() != MINOR_DIGITS) {
            throw new IllegalArgumentException("Only currencies with 2 minor digits are supported: " + currency);
        }
        if (amountMinor < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + amountMinor);
        }
    }

    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /** Parses an API decimal string such as {@code "1500.50"} as INR. */
    public static Money parse(String value) {
        return parse(value, INR);
    }

    /**
     * Parses a plain decimal string with at most 2 decimals. Rejects (never rounds) blank, signed,
     * exponent, grouped, over-precise and out-of-range values.
     */
    public static Money parse(String value, Currency currency) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "Amount must be a non-negative decimal with at most 2 decimal places.");
        }
        try {
            long minor = new BigDecimal(value).movePointRight(MINOR_DIGITS).longValueExact();
            return new Money(minor, currency);
        } catch (ArithmeticException e) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Amount is too large.");
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        long result = Math.subtractExact(amountMinor, other.amountMinor);
        if (result < 0) {
            throw new IllegalArgumentException("Subtraction would make money negative");
        }
        return new Money(result, currency);
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    /** Formats with exactly 2 decimals, e.g. {@code 150050 -> "1500.50"}. */
    public String toDecimalString() {
        return BigDecimal.valueOf(amountMinor, MINOR_DIGITS).toPlainString();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
