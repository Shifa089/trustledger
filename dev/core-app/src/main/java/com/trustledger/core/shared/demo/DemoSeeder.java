package com.trustledger.core.shared.demo;

import com.trustledger.core.shared.seed.FullWalletSeeder;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the demo data (M16): customer1 and customer2 with approved MIN and FULL KYC, a FULL, ACTIVE wallet each and
 * a ledger-backed opening balance (reference {@code DEMO_SEED}), through {@link FullWalletSeeder}. Idempotent: a wallet
 * that already exists keeps its balance, so restarting with {@code demo} never undoes what the demo did.
 * <p>
 * The seeded users hold no personal data (no e-mail, phone or PAN): {@code /me} shows them as {@code null}, and a
 * transfer to them goes by wallet ID. Officers, admin and auditor need no seed: their rows are created on first login.
 */
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final FullWalletSeeder writer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DemoSeeder(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.writer = new FullWalletSeeder(jdbc);
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** @return the number of wallets created by this call (0 when both were already there) */
    public int seed(DemoSeedProperties balances) {
        Integer created = tx.execute(status -> {
            Timestamp now = Timestamp.from(clock.instant());
            int wallets = 0;
            for (int n = 1; n <= DemoSeedIds.CUSTOMERS; n++) {
                if (writer.seed(DemoSeedIds.seedWallet(n), balances.balanceOf(n).amountMinor(), now)) {
                    wallets++;
                }
            }
            return wallets;
        });
        int result = created == null ? 0 : created;
        log.info("Demo seed: {} of {} customer wallets created, the rest already existed", result,
                DemoSeedIds.CUSTOMERS);
        return result;
    }
}
