package com.trustledger.core.shared.perf;

import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.seed.FullWalletSeeder;
import com.trustledger.core.shared.seed.SeedWallet;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the load-test data (M13): for each {@code n} in 1..count one FULL-tier wallet with the opening balance,
 * through {@link FullWalletSeeder} (user without personal data, approved MIN and FULL KYC, wallet, ledger account,
 * opening entry with reference {@code PERF_SEED}). See {@link FullWalletSeeder} for why this is SQL and why it is
 * idempotent. The IDs are deterministic ({@link PerfSeedIds}); all wallets are written in one transaction.
 */
public class PerfSeeder {

    static final String REFERENCE_TYPE = "PERF_SEED";

    private static final Logger log = LoggerFactory.getLogger(PerfSeeder.class);

    private final FullWalletSeeder writer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PerfSeeder(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.writer = new FullWalletSeeder(jdbc);
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** @return the number of wallets created by this call (0 when everything was already there) */
    public int seed(int count, Money openingBalance) {
        // Validates count and amount the same way as the configuration.
        new PerfSeedProperties(count, openingBalance);
        Integer created = tx.execute(status -> {
            Timestamp now = Timestamp.from(clock.instant());
            int wallets = 0;
            for (int n = 1; n <= count; n++) {
                if (writer.seed(seedWallet(n), openingBalance.amountMinor(), now)) {
                    wallets++;
                }
            }
            return wallets;
        });
        int result = created == null ? 0 : created;
        log.info("Perf seed: {} of {} wallets created, the rest already existed", result, count);
        return result;
    }

    static SeedWallet seedWallet(int n) {
        return new SeedWallet(PerfSeedIds.subject(n), PerfSeedIds.user(n),
                PerfSeedIds.id(PerfSeedIds.MIN_KYC_KIND, n), PerfSeedIds.id(PerfSeedIds.FULL_KYC_KIND, n),
                PerfSeedIds.wallet(n), PerfSeedIds.id(PerfSeedIds.ACCOUNT_KIND, n),
                PerfSeedIds.id(PerfSeedIds.ENTRY_KIND, n), PerfSeedIds.id(PerfSeedIds.DEBIT_LINE_KIND, n),
                PerfSeedIds.id(PerfSeedIds.CREDIT_LINE_KIND, n), REFERENCE_TYPE, PerfSeedIds.MAKER,
                PerfSeedIds.CHECKER);
    }
}
