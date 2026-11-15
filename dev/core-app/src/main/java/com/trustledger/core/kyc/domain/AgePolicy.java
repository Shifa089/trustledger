package com.trustledger.core.kyc.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Minimum-age rule for KYC (FR-KYC-1: 18+). Pure function; the caller supplies "today" from the injected
 * Clock in Asia/Kolkata, so a birthday counts from Indian midnight, not UTC midnight.
 * <p>
 * Someone born on 29 February turns N on 28 February in non-leap years ({@link LocalDate#plusYears}).
 */
public final class AgePolicy {

    private AgePolicy() {
    }

    public static boolean isAtLeast(int years, LocalDate dateOfBirth, LocalDate today) {
        Objects.requireNonNull(dateOfBirth, "dateOfBirth");
        Objects.requireNonNull(today, "today");
        if (years < 0) {
            throw new IllegalArgumentException("years must not be negative");
        }
        return !dateOfBirth.plusYears(years).isAfter(today);
    }
}
