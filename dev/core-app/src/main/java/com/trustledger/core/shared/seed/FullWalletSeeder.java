package com.trustledger.core.shared.seed;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes one FULL-tier test wallet straight into the tables (M13 perf data, M16 demo data): a user without personal
 * data, approved MIN and FULL KYC cases, a FULL, ACTIVE wallet with an opening balance, its {@code WALLET:<id>} ledger
 * account and an opening journal entry (debit {@code PLATFORM:GATEWAY_CLEARING}, credit the wallet), so the cached
 * balance equals the ledger-derived balance and reconciliation finds no drift.
 * <p>
 * <b>Why SQL and not the module APIs:</b> the real path (KYC maker-checker, events, wallet creation) is asynchronous,
 * audited and mails people; seeding through it would fill the audit trail with fake reviews. This is test data for
 * non-production profiles only, written into the tables those modules own, like V7's platform accounts. It lives in
 * {@code shared} because it belongs to no single module and depends on none of them (SQL only).
 * <p>
 * <b>Idempotent:</b> a wallet that already exists (same ID, or the user already has one) is left alone, and its ledger
 * rows are only written in the run that created it, so a restart never resets a balance that transfers have moved
 * since, and never posts a second opening entry. The caller runs it inside a transaction.
 */
public class FullWalletSeeder {

    private final JdbcTemplate jdbc;

    public FullWalletSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this call created the wallet, false if it already existed */
    public boolean seed(SeedWallet seed, long balanceMinor, Timestamp now) {
        if (balanceMinor <= 0) {
            throw new IllegalArgumentException("opening balance must be positive");
        }
        jdbc.update("""
                INSERT INTO identity.users (id, keycloak_subject, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0) ON CONFLICT DO NOTHING""",
                seed.user(), seed.subject(), now, now);
        // The subject may have been provisioned before (someone logged in first): use that row.
        UUID userId = jdbc.queryForObject("SELECT id FROM identity.users WHERE keycloak_subject = ?", UUID.class,
                seed.subject());

        jdbc.update("""
                INSERT INTO kyc.kyc_cases (id, user_id, level, status, created_at, updated_at, version)
                VALUES (?, ?, 'MIN', 'APPROVED', ?, ?, 0) ON CONFLICT DO NOTHING""",
                seed.minKycCase(), userId, now, now);
        jdbc.update("""
                INSERT INTO kyc.kyc_cases (id, user_id, level, status, verified_by, verified_at, approved_by,
                                           approved_at, created_at, updated_at, version)
                VALUES (?, ?, 'FULL', 'APPROVED', ?, ?, ?, ?, ?, ?, 0) ON CONFLICT DO NOTHING""",
                seed.fullKycCase(), userId, seed.maker(), now, seed.checker(), now, now, now);

        int inserted = jdbc.update("""
                INSERT INTO wallet.wallets (id, user_id, tier, status, balance_minor, held_minor, currency,
                                            ledger_account_id, created_at, updated_at, version)
                VALUES (?, ?, 'FULL', 'ACTIVE', ?, 0, 'INR', ?, ?, ?, 0) ON CONFLICT DO NOTHING""",
                seed.wallet(), userId, balanceMinor, seed.account(), now, now);
        if (inserted == 0) {
            return false;
        }

        // Only in the run that created the wallet: plain INSERTs, so an inconsistent database fails loudly.
        jdbc.update("""
                INSERT INTO ledger.ledger_accounts (id, code, type, normal_side, currency, created_at)
                VALUES (?, ?, 'LIABILITY', 'CREDIT', 'INR', ?)""",
                seed.account(), "WALLET:" + seed.wallet(), now);
        jdbc.update("""
                INSERT INTO ledger.journal_entries (id, entry_type, reference_type, reference_id, posted_at, created_at)
                VALUES (?, 'TOPUP', ?, ?, ?, ?)""",
                seed.entry(), seed.referenceType(), seed.wallet(), now, now);
        jdbc.update("""
                INSERT INTO ledger.journal_lines (id, entry_id, account_id, direction, amount_minor, line_no, created_at)
                SELECT ?, ?, id, 'DEBIT', ?, 1, ? FROM ledger.ledger_accounts WHERE code = 'PLATFORM:GATEWAY_CLEARING'""",
                seed.debitLine(), seed.entry(), balanceMinor, now);
        jdbc.update("""
                INSERT INTO ledger.journal_lines (id, entry_id, account_id, direction, amount_minor, line_no, created_at)
                VALUES (?, ?, ?, 'CREDIT', ?, 2, ?)""",
                seed.creditLine(), seed.entry(), seed.account(), balanceMinor, now);
        return true;
    }
}
