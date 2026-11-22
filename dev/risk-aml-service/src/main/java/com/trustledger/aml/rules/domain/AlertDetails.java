package com.trustledger.aml.rules.domain;

import java.util.Objects;

/**
 * Why a rule fired, stored in {@code aml.alerts.details} and later shown to the compliance officer and in the STR
 * report. Counts, amounts, windows and IDs only: never personal data.
 *
 * @param description e.g. {@code "6 outgoing transfers in PT10M (more than 5); latest transfer <id>"}
 */
public record AlertDetails(String description) {

    public AlertDetails {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }

    /** Paise as rupees with two decimals, e.g. {@code 4600000 -> "46000.00"}; no floating point. */
    static String rupees(long amountMinor) {
        return "%d.%02d".formatted(amountMinor / 100, Math.abs(amountMinor % 100));
    }
}
