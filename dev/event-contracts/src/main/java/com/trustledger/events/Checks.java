package com.trustledger.events;

import java.util.Objects;
import java.util.regex.Pattern;

/** Field checks shared by the contract records. A producer bug fails at construction, never on the consumer side. */
final class Checks {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private Checks() {
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static void positive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive: " + amountMinor);
        }
    }

    static void currency(String currency) {
        Objects.requireNonNull(currency, "currency");
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code such as INR: " + currency);
        }
    }

    static void notBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
