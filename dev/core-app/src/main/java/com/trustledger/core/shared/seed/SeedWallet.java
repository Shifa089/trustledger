package com.trustledger.core.shared.seed;

import java.util.Objects;
import java.util.UUID;

/**
 * Every ID one seeded FULL-tier wallet needs, fixed by the caller so a second run finds the same rows
 * ({@link FullWalletSeeder}). Fake test data only.
 *
 * @param subject       the Keycloak user ID ({@code sub}) the seeded user is provisioned under
 * @param referenceType the opening journal entry's {@code reference_type} (e.g. {@code PERF_SEED}, {@code DEMO_SEED})
 * @param maker         the fake officer who verified the FULL KYC case
 * @param checker       the fake officer who approved it; must differ from the maker (FR-KYC-3)
 */
public record SeedWallet(String subject, UUID user, UUID minKycCase, UUID fullKycCase, UUID wallet, UUID account,
        UUID entry, UUID debitLine, UUID creditLine, String referenceType, UUID maker, UUID checker) {

    public SeedWallet {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(minKycCase, "minKycCase");
        Objects.requireNonNull(fullKycCase, "fullKycCase");
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(debitLine, "debitLine");
        Objects.requireNonNull(creditLine, "creditLine");
        Objects.requireNonNull(referenceType, "referenceType");
        Objects.requireNonNull(maker, "maker");
        Objects.requireNonNull(checker, "checker");
        if (maker.equals(checker)) {
            throw new IllegalArgumentException("maker and checker of a seeded KYC approval must differ");
        }
    }
}
