package com.trustledger.core.shared.perf;

import java.util.UUID;

/**
 * Deterministic IDs of the load-test data (M13). Wallet {@code n} (1-based) always has the same Keycloak subject,
 * user, wallet and ledger IDs, so three places agree without talking to each other:
 * <ul>
 * <li>{@link PerfSeeder} writes the rows;</li>
 * <li>{@code load-tests/keycloak-perf-users.sh} creates Keycloak user {@code perfNNN} with ID {@link #subject} (so
 * the token's {@code sub} is the seeded {@code keycloak_subject} and JIT provisioning just finds the row);</li>
 * <li>{@code load-tests/transfers.js} sends to {@link #wallet} IDs.</li>
 * </ul>
 * Format: {@code <kind>-0000-4000-8000-<n as 12 decimal digits>}. {@code PerfSeedIdsTest} checks the two scripts use
 * the same prefixes. Fake test data only.
 */
public final class PerfSeedIds {

    /** Largest {@code n}: usernames are {@code perf001}..{@code perf999}. */
    public static final int MAX_WALLETS = 999;

    public static final String SUBJECT_KIND = "0e7f0001";
    public static final String USER_KIND = "0e7f0002";
    public static final String WALLET_KIND = "0e7f0003";
    static final String ACCOUNT_KIND = "0e7f0004";
    static final String ENTRY_KIND = "0e7f0005";
    static final String DEBIT_LINE_KIND = "0e7f0006";
    static final String CREDIT_LINE_KIND = "0e7f0007";
    static final String MIN_KYC_KIND = "0e7f0008";
    static final String FULL_KYC_KIND = "0e7f0009";

    /** The fake maker and checker of the seeded FULL KYC approvals (different, as FR-KYC-3 requires). */
    static final UUID MAKER = UUID.fromString("0e7f00ff-0000-4000-8000-000000000001");
    static final UUID CHECKER = UUID.fromString("0e7f00ff-0000-4000-8000-000000000002");

    private PerfSeedIds() {
    }

    public static String username(int n) {
        return "perf%03d".formatted(check(n));
    }

    /** Keycloak user ID, and so the {@code sub} claim of their tokens. */
    public static String subject(int n) {
        return id(SUBJECT_KIND, n).toString();
    }

    public static UUID user(int n) {
        return id(USER_KIND, n);
    }

    public static UUID wallet(int n) {
        return id(WALLET_KIND, n);
    }

    static UUID id(String kind, int n) {
        return UUID.fromString("%s-0000-4000-8000-%012d".formatted(kind, check(n)));
    }

    private static int check(int n) {
        if (n < 1 || n > MAX_WALLETS) {
            throw new IllegalArgumentException("perf wallet number must be 1.." + MAX_WALLETS + ": " + n);
        }
        return n;
    }
}
