package com.trustledger.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.LedgerSql.Line;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V7/V8: the database safety nets on their own (SPEC §4), with raw SQL that bypasses the Java code entirely.
 * The deferred trigger refuses an unbalanced entry at COMMIT; the append-only triggers refuse UPDATE, DELETE
 * and TRUNCATE (FR-L-2); constraints refuse duplicates and malformed rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerMigrationIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerSql sql;

    /** The database refused it with a message containing {@code text} (a constraint name or trigger text). */
    private static void refused(ThrowingCallable action, String text) {
        assertThatThrownBy(action).rootCause().hasMessageContaining(text);
    }

    @Test
    void platformAccountsAreSeeded() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select code, type, normal_side, currency from ledger.ledger_accounts
                where code like 'PLATFORM:%' order by code""");

        assertThat(rows).containsExactly(
                Map.of("code", "PLATFORM:GATEWAY_CLEARING", "type", "ASSET", "normal_side", "DEBIT",
                        "currency", "INR"),
                Map.of("code", "PLATFORM:SUSPENSE", "type", "LIABILITY", "normal_side", "CREDIT",
                        "currency", "INR"));
    }

    /**
     * Lines arrive one statement at a time, so mid-transaction the entry is unbalanced. That is why the check is
     * DEFERRED: only the state at COMMIT counts.
     */
    @Test
    void entryMayBeUnbalancedMidTransactionIfBalancedAtCommit() {
        UUID a = sql.newLiability();
        UUID b = sql.newLiability();
        UUID entry = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            sql.insertEntry(entry, "TOPUP", "TOPUP", UUID.randomUUID(), null, null);
            sql.insertLine(entry, 1, Line.debit(a, 100));
            assertThat(jdbc.queryForObject("select count(*) from ledger.journal_lines where entry_id = ?",
                    Integer.class, entry)).isEqualTo(1);
            sql.insertLine(entry, 2, Line.credit(b, 60));
            sql.insertLine(entry, 3, Line.credit(b, 40));
        });

        assertThat(sql.entryExists(entry)).isTrue();
    }

    @Test
    void unbalancedEntryIsRefusedAtCommit() {
        UUID entry = UUID.randomUUID();

        refused(() -> sql.post(entry, "TOPUP", UUID.randomUUID(), null, null,
                Line.debit(sql.newLiability(), 100), Line.credit(sql.newLiability(), 99)), "unbalanced");

        assertThat(sql.entryExists(entry)).isFalse();
    }

    @Test
    void singleLineEntryIsRefusedAtCommit() {
        UUID entry = UUID.randomUUID();

        refused(() -> sql.post(entry, "TOPUP", UUID.randomUUID(), null, null, Line.debit(sql.newLiability(), 100)),
                "at least 2 are required");

        assertThat(sql.entryExists(entry)).isFalse();
    }

    @Test
    void entryWithoutLinesIsRefusedAtCommit() {
        UUID entry = UUID.randomUUID();

        refused(() -> sql.post(entry, "TOPUP", UUID.randomUUID(), null, null), "has 0 line(s)");

        assertThat(sql.entryExists(entry)).isFalse();
    }

    @Test
    void entryMixingCurrenciesIsRefusedAtCommit() {
        UUID entry = UUID.randomUUID();
        UUID usd = sql.newAccount("LIABILITY", "CREDIT", "USD");

        refused(() -> sql.post(entry, "TOPUP", UUID.randomUUID(), null, null,
                Line.debit(sql.newLiability(), 100), Line.credit(usd, 100)), "mixes 2 currencies");

        assertThat(sql.entryExists(entry)).isFalse();
    }

    @Test
    void journalLinesRefuseUpdateAndDelete() {
        UUID entry = sql.post(Line.debit(sql.newLiability(), 100), Line.credit(sql.newLiability(), 100));

        refused(() -> jdbc.update("update ledger.journal_lines set amount_minor = 1 where entry_id = ?", entry),
                "ledger.journal_lines is append-only: UPDATE is not allowed");
        refused(() -> jdbc.update("delete from ledger.journal_lines where entry_id = ?", entry),
                "ledger.journal_lines is append-only: DELETE is not allowed");

        assertThat(jdbc.queryForList("select amount_minor from ledger.journal_lines where entry_id = ?", Long.class,
                entry)).containsExactly(100L, 100L);
    }

    @Test
    void journalEntriesRefuseUpdateAndDelete() {
        UUID entry = sql.post(Line.debit(sql.newLiability(), 100), Line.credit(sql.newLiability(), 100));

        refused(() -> jdbc.update("update ledger.journal_entries set reference_type = 'X' where id = ?", entry),
                "ledger.journal_entries is append-only: UPDATE is not allowed");
        refused(() -> jdbc.update("delete from ledger.journal_entries where id = ?", entry),
                "ledger.journal_entries is append-only: DELETE is not allowed");

        assertThat(sql.entryExists(entry)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal_lines", "journal_entries"})
    void journalTablesRefuseTruncate(String table) {
        sql.post(Line.debit(sql.newLiability(), 100), Line.credit(sql.newLiability(), 100));

        refused(() -> jdbc.execute("truncate ledger." + table + " cascade"), "append-only: TRUNCATE");

        assertThat(jdbc.queryForObject("select count(*) from ledger." + table, Long.class)).isPositive();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100})
    void nonPositiveAmountIsRefused(long amount) {
        UUID entry = UUID.randomUUID();

        refused(() -> sql.post(entry, "TOPUP", UUID.randomUUID(), null, null,
                Line.debit(sql.newLiability(), amount), Line.credit(sql.newLiability(), amount)),
                "ck_journal_lines_amount_positive");
    }

    @Test
    void sameBusinessEventCannotBePostedTwice() {
        UUID a = sql.newLiability();
        UUID b = sql.newLiability();
        UUID reference = UUID.randomUUID();
        sql.post(UUID.randomUUID(), "TOPUP", reference, null, null, Line.debit(a, 1), Line.credit(b, 1));

        refused(() -> sql.post(UUID.randomUUID(), "TOPUP", reference, null, null, Line.debit(a, 1),
                Line.credit(b, 1)), "uk_journal_entries_reference");
    }

    @Test
    void sameReferenceUnderAnotherEntryTypeIsAllowed() {
        UUID a = sql.newLiability();
        UUID b = sql.newLiability();
        UUID reference = UUID.randomUUID();
        sql.post(UUID.randomUUID(), "TOPUP", reference, null, null, Line.debit(a, 1), Line.credit(b, 1));
        UUID second = UUID.randomUUID();

        sql.post(second, "P2P_TRANSFER", reference, null, null, Line.debit(a, 1), Line.credit(b, 1));

        assertThat(sql.entryExists(second)).isTrue();
    }

    @Test
    void anEntryIsReversedAtMostOnce() {
        UUID a = sql.newLiability();
        UUID b = sql.newLiability();
        UUID original = sql.post(Line.debit(a, 5), Line.credit(b, 5));
        sql.post(UUID.randomUUID(), "REVERSAL", original, original, "first", Line.credit(a, 5), Line.debit(b, 5));

        // Same reverses_entry_id under a different reference: only uk_journal_entries_reverses stops it.
        refused(() -> tx.executeWithoutResult(status -> {
            UUID second = UUID.randomUUID();
            sql.insertEntry(second, "REVERSAL", "MANUAL", UUID.randomUUID(), original, "second");
            sql.insertLines(second, Line.credit(a, 5), Line.debit(b, 5));
        }), "uk_journal_entries_reverses");
    }

    @Test
    void reversalNeedsItsTargetAndReasonAndOnlyAReversalHasThem() {
        UUID target = sql.post(Line.debit(sql.newLiability(), 5), Line.credit(sql.newLiability(), 5));

        refused(() -> sql.insertEntry(UUID.randomUUID(), "REVERSAL", "JOURNAL_ENTRY", target, null, "why"),
                "ck_journal_entries_reversal");
        refused(() -> sql.insertEntry(UUID.randomUUID(), "REVERSAL", "JOURNAL_ENTRY", target, target, null),
                "ck_journal_entries_reason");
        refused(() -> sql.insertEntry(UUID.randomUUID(), "REVERSAL", "JOURNAL_ENTRY", target, target, "  "),
                "ck_journal_entries_reason");
        refused(() -> sql.insertEntry(UUID.randomUUID(), "TOPUP", "TOPUP", target, target, null),
                "ck_journal_entries_reversal");
        refused(() -> sql.insertEntry(UUID.randomUUID(), "TOPUP", "TOPUP", target, null, "why"),
                "ck_journal_entries_reason");
    }

    @Test
    void entryTypeAndReferenceTypeAreRestricted() {
        refused(() -> sql.insertEntry(UUID.randomUUID(), "GIFT", "TOPUP", UUID.randomUUID(), null, null),
                "ck_journal_entries_type");
        refused(() -> sql.insertEntry(UUID.randomUUID(), "TOPUP", "topup", UUID.randomUUID(), null, null),
                "ck_journal_entries_reference_type");
    }

    @Test
    void lineOnAnUnknownAccountIsRefused() {
        refused(() -> sql.post(UUID.randomUUID(), "TOPUP", UUID.randomUUID(), null, null,
                Line.debit(UUID.randomUUID(), 1), Line.credit(sql.newLiability(), 1)), "fk_journal_lines_account");
    }

    @Test
    void accountTypeFixesTheNormalSide() {
        refused(() -> sql.newAccount("ASSET", "CREDIT", "INR"), "ck_ledger_accounts_side_matches_type");
        refused(() -> sql.newAccount("LIABILITY", "DEBIT", "INR"), "ck_ledger_accounts_side_matches_type");
        refused(() -> sql.newAccount("EQUITY", "CREDIT", "INR"), "ck_ledger_accounts_type");
        refused(() -> sql.newAccount("LIABILITY", "CREDIT", "inr"), "ck_ledger_accounts_currency");
    }

    @Test
    void accountCodeIsUniqueAndWellFormed() {
        String insert = """
                insert into ledger.ledger_accounts (id, code, type, normal_side, currency, created_at)
                values (?, ?, 'LIABILITY', 'CREDIT', 'INR', ?)""";

        refused(() -> jdbc.update(insert, UUID.randomUUID(), AccountCodes.GATEWAY_CLEARING, LedgerSql.NOW),
                "uk_ledger_accounts_code");
        refused(() -> jdbc.update(insert, UUID.randomUUID(), "wallet:abc", LedgerSql.NOW), "ck_ledger_accounts_code");
        refused(() -> jdbc.update(insert, UUID.randomUUID(), "WALLET:a b", LedgerSql.NOW), "ck_ledger_accounts_code");
    }

    @Test
    void refusalsAreDataAccessExceptions() {
        assertThatThrownBy(() -> sql.newAccount("ASSET", "CREDIT", "INR")).isInstanceOf(DataAccessException.class);
    }
}
