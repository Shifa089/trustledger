package com.trustledger.core.shared.perf;

import com.trustledger.core.shared.Money;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the load-test data (M13): for each {@code n} in 1..count a user (no personal data), approved MIN and FULL
 * KYC cases, a FULL, ACTIVE wallet with the opening balance, its {@code WALLET:<id>} ledger account and an opening
 * journal entry (debit {@code PLATFORM:GATEWAY_CLEARING}, credit the wallet; reference {@code PERF_SEED}), so the
 * cached balance equals the ledger-derived balance and reconciliation finds no drift.
 * <p>
 * <b>Why SQL and not the module APIs:</b> the real path (KYC maker-checker, events, wallet creation) is asynchronous,
 * audited and mails people; seeding 200 wallets through it would be slow and would fill the audit trail with fake
 * reviews. This is test data for one profile, written straight into the tables those modules own, like V7's platform
 * accounts. It lives in {@code shared} because it belongs to no single module and depends on none of them (SQL only).
 * <p>
 * <b>Idempotent:</b> the IDs are deterministic ({@link PerfSeedIds}). A wallet that already exists is left alone, and
 * its ledger rows are only written in the run that created it, so a restart never resets a balance that transfers
 * have moved since, and never posts a second opening entry. All of it runs in one transaction.
 */
public class PerfSeeder {

    static final String REFERENCE_TYPE = "PERF_SEED";

    private static final Logger log = LoggerFactory.getLogger(PerfSeeder.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PerfSeeder(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
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
                if (seedOne(n, openingBalance.amountMinor(), now)) {
                    wallets++;
                }
            }
            return wallets;
        });
        int result = created == null ? 0 : created;
        log.info("Perf seed: {} of {} wallets created, the rest already existed", result, count);
        return result;
    }

    private boolean seedOne(int n, long balanceMinor, Timestamp now) {
        String subject = PerfSeedIds.subject(n);
        jdbc.update("""
                INSERT INTO identity.users (id, keycloak_subject, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0) ON CONFLICT DO NOTHING""",
                PerfSeedIds.user(n), subject, now, now);
        // The subject may have been provisioned before (someone used the perf user first): use that row.
        UUID userId = jdbc.queryForObject("SELECT id FROM identity.users WHERE keycloak_subject = ?", UUID.class,
                subject);

        jdbc.update("""
                INSERT INTO kyc.kyc_cases (id, user_id, level, status, created_at, updated_at, version)
                VALUES (?, ?, 'MIN', 'APPROVED', ?, ?, 0) ON CONFLICT DO NOTHING""",
                PerfSeedIds.id(PerfSeedIds.MIN_KYC_KIND, n), userId, now, now);
        jdbc.update("""
                INSERT INTO kyc.kyc_cases (id, user_id, level, status, verified_by, verified_at, approved_by,
                                           approved_at, created_at, updated_at, version)
                VALUES (?, ?, 'FULL', 'APPROVED', ?, ?, ?, ?, ?, ?, 0) ON CONFLICT DO NOTHING""",
                PerfSeedIds.id(PerfSeedIds.FULL_KYC_KIND, n), userId, PerfSeedIds.MAKER, now, PerfSeedIds.CHECKER,
                now, now, now);

        UUID walletId = PerfSeedIds.wallet(n);
        UUID accountId = PerfSeedIds.id(PerfSeedIds.ACCOUNT_KIND, n);
        int inserted = jdbc.update("""
                INSERT INTO wallet.wallets (id, user_id, tier, status, balance_minor, held_minor, currency,
                                            ledger_account_id, created_at, updated_at, version)
                VALUES (?, ?, 'FULL', 'ACTIVE', ?, 0, 'INR', ?, ?, ?, 0) ON CONFLICT DO NOTHING""",
                walletId, userId, balanceMinor, accountId, now, now);
        if (inserted == 0) {
            return false;
        }

        // Only in the run that created the wallet: plain INSERTs, so an inconsistent database fails loudly.
        jdbc.update("""
                INSERT INTO ledger.ledger_accounts (id, code, type, normal_side, currency, created_at)
                VALUES (?, ?, 'LIABILITY', 'CREDIT', 'INR', ?)""",
                accountId, "WALLET:" + walletId, now);
        UUID entryId = PerfSeedIds.id(PerfSeedIds.ENTRY_KIND, n);
        jdbc.update("""
                INSERT INTO ledger.journal_entries (id, entry_type, reference_type, reference_id, posted_at, created_at)
                VALUES (?, 'TOPUP', ?, ?, ?, ?)""",
                entryId, REFERENCE_TYPE, walletId, now, now);
        jdbc.update("""
                INSERT INTO ledger.journal_lines (id, entry_id, account_id, direction, amount_minor, line_no, created_at)
                SELECT ?, ?, id, 'DEBIT', ?, 1, ? FROM ledger.ledger_accounts WHERE code = 'PLATFORM:GATEWAY_CLEARING'""",
                PerfSeedIds.id(PerfSeedIds.DEBIT_LINE_KIND, n), entryId, balanceMinor, now);
        jdbc.update("""
                INSERT INTO ledger.journal_lines (id, entry_id, account_id, direction, amount_minor, line_no, created_at)
                VALUES (?, ?, ?, 'CREDIT', ?, 2, ?)""",
                PerfSeedIds.id(PerfSeedIds.CREDIT_LINE_KIND, n), entryId, accountId, balanceMinor, now);
        return true;
    }
}
