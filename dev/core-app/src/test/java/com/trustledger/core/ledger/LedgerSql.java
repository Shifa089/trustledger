package com.trustledger.core.ledger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test support: writes ledger rows with raw SQL, bypassing {@code LedgerPostingService}. Used to test the
 * database safety nets on their own, and to set up data for tests that must not depend on {@code post()}.
 */
@Component
class LedgerSql {

    static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-15T10:00:00Z"));

    /** One raw journal line. */
    record Line(UUID accountId, String direction, long amountMinor) {

        static Line debit(UUID accountId, long amountMinor) {
            return new Line(accountId, "DEBIT", amountMinor);
        }

        static Line credit(UUID accountId, long amountMinor) {
            return new Line(accountId, "CREDIT", amountMinor);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    LedgerSql(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    UUID accountId(String code) {
        return jdbc.queryForObject("select id from ledger.ledger_accounts where code = ?", UUID.class, code);
    }

    /** A fresh account with a unique {@code TEST:<uuid>} code. */
    UUID newAccount(String type, String normalSide, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into ledger.ledger_accounts (id, code, type, normal_side, currency, created_at)
                values (?, ?, ?, ?, ?, ?)""", id, "TEST:" + id, type, normalSide, currency, NOW);
        return id;
    }

    UUID newLiability() {
        return newAccount("LIABILITY", "CREDIT", "INR");
    }

    /** Inserts an entry row in the current transaction (or on its own, in auto-commit). */
    void insertEntry(UUID id, String type, String referenceType, UUID referenceId, UUID reversesId, String reason) {
        jdbc.update("""
                insert into ledger.journal_entries (id, entry_type, reference_type, reference_id, reverses_entry_id,
                    reason, posted_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, type, referenceType, referenceId, reversesId, reason, NOW, NOW);
    }

    /** Lines numbered 1..n. */
    void insertLines(UUID entryId, Line... lines) {
        for (int i = 0; i < lines.length; i++) {
            insertLine(entryId, i + 1, lines[i]);
        }
    }

    void insertLine(UUID entryId, int lineNo, Line line) {
        jdbc.update("""
                insert into ledger.journal_lines (id, entry_id, account_id, direction, amount_minor, line_no,
                    created_at) values (?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), entryId, line.accountId(), line.direction(), line.amountMinor(), lineNo, NOW);
    }

    /**
     * An entry and its lines in one committed transaction: the deferred balance check runs at that commit. The
     * reference type is {@code JOURNAL_ENTRY} for a reversal and {@code PAYMENT} otherwise, so the same
     * reference ID can be reused under different entry types.
     */
    void post(UUID entryId, String type, UUID referenceId, UUID reversesId, String reason, Line... lines) {
        tx.executeWithoutResult(status -> {
            insertEntry(entryId, type, type.equals("REVERSAL") ? "JOURNAL_ENTRY" : "PAYMENT", referenceId, reversesId,
                    reason);
            insertLines(entryId, lines);
        });
    }

    /** A committed TOPUP entry with a fresh reference. */
    UUID post(Line... lines) {
        UUID id = UUID.randomUUID();
        post(id, "TOPUP", UUID.randomUUID(), null, null, lines);
        return id;
    }

    boolean entryExists(UUID id) {
        return jdbc.queryForObject("select count(*) from ledger.journal_entries where id = ?", Integer.class, id) == 1;
    }
}
