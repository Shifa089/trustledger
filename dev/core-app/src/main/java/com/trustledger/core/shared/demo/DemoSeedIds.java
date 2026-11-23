package com.trustledger.core.shared.demo;

import com.trustledger.core.shared.seed.SeedWallet;
import java.util.UUID;

/**
 * Fixed IDs of the demo data (M16). Customer {@code n} (1 or 2) is Keycloak user {@code customer<n>}, whose ID is set
 * to {@link #subject} in {@code infra/keycloak/realm-trustledger.json}, so a token's {@code sub} is the seeded
 * {@code keycloak_subject} and JIT provisioning finds the seeded user and wallet. {@code DemoSeedIdsTest} checks that
 * the realm file and this class agree.
 * <p>
 * Format: {@code de30000<kind>-0000-4000-8000-<n as 12 digits>}, distinct from the perf data ({@code 0e7f...}).
 */
public final class DemoSeedIds {

    public static final int CUSTOMERS = 2;
    static final String REFERENCE_TYPE = "DEMO_SEED";

    static final String SUBJECT_KIND = "de300001";
    static final String USER_KIND = "de300002";
    static final String WALLET_KIND = "de300003";
    static final String ACCOUNT_KIND = "de300004";
    static final String ENTRY_KIND = "de300005";
    static final String DEBIT_LINE_KIND = "de300006";
    static final String CREDIT_LINE_KIND = "de300007";
    static final String MIN_KYC_KIND = "de300008";
    static final String FULL_KYC_KIND = "de300009";

    /** The fake maker and checker of the seeded FULL KYC approvals (different, as FR-KYC-3 requires). */
    static final UUID MAKER = UUID.fromString("de3000ff-0000-4000-8000-000000000001");
    static final UUID CHECKER = UUID.fromString("de3000ff-0000-4000-8000-000000000002");

    private DemoSeedIds() {
    }

    public static String username(int n) {
        return "customer" + check(n);
    }

    /** Keycloak user ID of {@code customer<n>}, and so the {@code sub} claim of their tokens. */
    public static String subject(int n) {
        return id(SUBJECT_KIND, n).toString();
    }

    public static UUID user(int n) {
        return id(USER_KIND, n);
    }

    public static UUID wallet(int n) {
        return id(WALLET_KIND, n);
    }

    static SeedWallet seedWallet(int n) {
        return new SeedWallet(subject(n), user(n), id(MIN_KYC_KIND, n), id(FULL_KYC_KIND, n), wallet(n),
                id(ACCOUNT_KIND, n), id(ENTRY_KIND, n), id(DEBIT_LINE_KIND, n), id(CREDIT_LINE_KIND, n),
                REFERENCE_TYPE, MAKER, CHECKER);
    }

    static UUID id(String kind, int n) {
        return UUID.fromString("%s-0000-4000-8000-%012d".formatted(kind, check(n)));
    }

    private static int check(int n) {
        if (n < 1 || n > CUSTOMERS) {
            throw new IllegalArgumentException("demo customer number must be 1.." + CUSTOMERS + ": " + n);
        }
        return n;
    }
}
