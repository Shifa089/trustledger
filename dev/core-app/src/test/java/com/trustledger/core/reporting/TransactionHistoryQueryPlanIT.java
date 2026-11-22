package com.trustledger.core.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M11 / docs/perf.md: {@code EXPLAIN (ANALYZE, BUFFERS)} of the history queries on a seeded table, so the plan is
 * measured, not assumed. About 60,000 history rows: one "hot" wallet with 10,000 (6,000 sent, 2,000 received,
 * 2,000 top-ups) among 400 other wallets. The test fails if a query plans a sequential scan of transfers or top-ups,
 * or if the newest-first page has to sort the hot wallet's rows; the plans are written to
 * {@code target/perf/history-explain.txt} for docs/perf.md.
 * <p>
 * The SQL is what Hibernate generates for the Specifications (same predicates and order), with literal bind values.
 * Seeded rows use wallet IDs starting {@code 0000beef-} and are deleted afterwards.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionHistoryQueryPlanIT {

    private static final String HOT = "0000beef-0000-4000-8000-000000000000";
    private static final String SELECT = """
            select source_id, type, status, amount_minor, currency, counterparty_wallet_id, note, created_at
            from payments.wallet_transactions wt""";

    @Autowired
    JdbcTemplate jdbc;

    private final List<String> report = new ArrayList<>();

    @BeforeAll
    void seed() {
        // 400 background wallets: 0000beef-0000-4000-8000-000000000001 .. 400
        String wallet = "cast('0000beef-0000-4000-8000-' || lpad((%s)::text, 12, '0') as uuid)";
        // Background: 40,000 completed transfers between background wallets, 10,000 captured top-ups.
        jdbc.update("""
                insert into payments.transfers (id, sender_wallet_id, receiver_wallet_id, amount_minor, currency,
                    status, journal_entry_id, created_at, updated_at, version)
                select gen_random_uuid(), %s, %s, 100 + g, 'INR', 'COMPLETED', gen_random_uuid(),
                       timestamptz '2026-01-01' + g * interval '7 minutes', timestamptz '2026-01-01', 0
                from generate_series(1, 40000) g""".formatted(wallet.formatted("1 + g % 400"),
                wallet.formatted("1 + (g + 7) % 400")));
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id,
                    gateway_payment_id, status, journal_entry_id, created_at, updated_at, version)
                select id, %s, 1000 + g, 'INR', 'order_' || id, 'pay_' || id, 'CAPTURED', gen_random_uuid(),
                       timestamptz '2026-01-01' + g * interval '29 minutes', timestamptz '2026-01-01', 0
                from (select gen_random_uuid() id, g from generate_series(1, 10000) g) s"""
                .formatted(wallet.formatted("1 + g % 400")));
        // The hot wallet: 6,000 sent (every 50th held for review), 2,000 received, 2,000 top-ups.
        jdbc.update("""
                insert into payments.transfers (id, sender_wallet_id, receiver_wallet_id, amount_minor, currency,
                    status, journal_entry_id, created_at, updated_at, version)
                select gen_random_uuid(), cast(? as uuid), %s, 500 + g, 'INR',
                       case when g %% 50 = 0 then 'HELD_FOR_REVIEW' else 'COMPLETED' end,
                       case when g %% 50 = 0 then null else gen_random_uuid() end,
                       timestamptz '2026-01-01' + g * interval '41 minutes', timestamptz '2026-01-01', 0
                from generate_series(1, 6000) g""".formatted(wallet.formatted("1 + g % 400")), HOT);
        jdbc.update("""
                insert into payments.transfers (id, sender_wallet_id, receiver_wallet_id, amount_minor, currency,
                    status, journal_entry_id, created_at, updated_at, version)
                select gen_random_uuid(), %s, cast(? as uuid), 700 + g, 'INR', 'COMPLETED', gen_random_uuid(),
                       timestamptz '2026-01-01' + g * interval '113 minutes', timestamptz '2026-01-01', 0
                from generate_series(1, 2000) g""".formatted(wallet.formatted("1 + g % 400")), HOT);
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id,
                    gateway_payment_id, status, journal_entry_id, created_at, updated_at, version)
                select id, cast(? as uuid), 2000 + g, 'INR', 'order_' || id, 'pay_' || id, 'CAPTURED',
                       gen_random_uuid(), timestamptz '2026-01-01' + g * interval '127 minutes',
                       timestamptz '2026-01-01', 0
                from (select gen_random_uuid() id, g from generate_series(1, 2000) g) s""", HOT);
        jdbc.execute("analyze payments.transfers");
        jdbc.execute("analyze payments.topups");
    }

    @AfterAll
    void cleanUp() throws IOException {
        jdbc.update("delete from payments.transfers where sender_wallet_id::text like '0000beef-%'"
                + " or receiver_wallet_id::text like '0000beef-%'");
        jdbc.update("delete from payments.topups where wallet_id::text like '0000beef-%'");
        Path out = Path.of("target", "perf", "history-explain.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
    }

    private String explain(String title, String sql, Object... args) {
        List<String> lines = jdbc.queryForList("explain (analyze, buffers) " + sql, String.class, args);
        String plan = String.join("\n", lines);
        report.add("=== " + title + " ===");
        report.add(sql);
        report.add("--");
        report.addAll(lines);
        report.add("");
        System.out.println("=== " + title + " ===\n" + plan);
        return plan;
    }

    private static void assertIndexOnly(String plan) {
        assertThat(plan).doesNotContain("Seq Scan on transfers").doesNotContain("Seq Scan on topups");
    }

    @Test
    void historyRowCount() {
        assertThat(jdbc.queryForObject("select count(*) from payments.wallet_transactions where wallet_id = cast(? as uuid) and visible",
                Long.class, HOT)).isEqualTo(10_000L);
    }

    @Test
    void firstPageNewestFirstIsAMergeOfIndexScansWithoutASort() {
        String plan = explain("offset page 0, newest first (default)", SELECT + """
                 where wt.wallet_id = cast(? as uuid) and wt.visible
                order by wt.created_at desc, wt.source_id desc
                offset 0 rows fetch first 20 rows only""", HOT);

        assertIndexOnly(plan);
        assertThat(plan).contains("Merge Append").contains("Index Scan Backward");
        assertThat(plan).doesNotContain("Sort Method");
    }

    @Test
    void theCountQueryOfOffsetPagingReadsOnlyTheWallet() {
        assertIndexOnly(explain("offset count", "select count(*) from payments.wallet_transactions wt"
                + " where wt.wallet_id = cast(? as uuid) and wt.visible", HOT));
    }

    @Test
    void aDeepOffsetPageStillWalksEveryEarlierRow() {
        String plan = explain("offset page 450 (offset 9000), newest first", SELECT + """
                 where wt.wallet_id = cast(? as uuid) and wt.visible
                order by wt.created_at desc, wt.source_id desc
                offset 9000 rows fetch first 20 rows only""", HOT);

        assertIndexOnly(plan);
    }

    @Test
    void theKeysetSliceAtTheSameDepthStartsAtTheCursor() {
        // The cursor = the 9,000th row newest first, i.e. where the deep offset page above starts.
        var row = jdbc.queryForMap("""
                select created_at, source_id from payments.wallet_transactions where wallet_id = cast(? as uuid) and visible
                order by created_at desc, source_id desc offset 8999 limit 1""", HOT);
        Timestamp at = (Timestamp) row.get("created_at");
        UUID id = (UUID) row.get("source_id");

        String plan = explain("keyset slice after row 9000, newest first", SELECT + """
                 where wt.wallet_id = cast(? as uuid) and wt.visible and wt.created_at <= ?
                  and (wt.created_at < ? or (wt.created_at = ? and wt.source_id < ?))
                order by wt.created_at desc, wt.source_id desc
                fetch first 21 rows only""", HOT, at, at, at, id);

        assertIndexOnly(plan);
        assertThat(plan).contains("Merge Append").doesNotContain("Sort Method");
    }

    @Test
    void aTypeFilterSkipsTheOtherBranches() {
        String plan = explain("offset page 0, type=TOPUP", SELECT + """
                 where wt.wallet_id = cast(? as uuid) and wt.visible and wt.type = 'TOPUP'
                order by wt.created_at desc, wt.source_id desc
                offset 0 rows fetch first 20 rows only""", HOT);

        assertIndexOnly(plan);
        assertThat(plan).contains("topups");
    }

    @Test
    void aMonthWindowUsesTheIndexRange() {
        String plan = explain("offset page 0, one month (from/to)", SELECT + """
                 where wt.wallet_id = cast(? as uuid) and wt.visible and wt.created_at >= ? and wt.created_at < ?
                order by wt.created_at desc, wt.source_id desc
                offset 0 rows fetch first 20 rows only""", HOT, Timestamp.from(Instant.parse("2026-03-31T18:30:00Z")),
                Timestamp.from(Instant.parse("2026-04-30T18:30:00Z")));

        assertIndexOnly(plan);
        assertThat(plan).contains("created_at >=");
    }

    /** Sorting by amount has no index (a whitelisted but rarer order): a top-N sort of one wallet's rows. */
    @Test
    void sortingByAmountSortsOnlyTheWalletsRows() {
        String plan = explain("offset page 0, sort=amount,desc", SELECT + """
                 where wt.wallet_id = cast(? as uuid) and wt.visible
                order by wt.amount_minor desc, wt.created_at desc, wt.source_id desc
                offset 0 rows fetch first 20 rows only""", HOT);

        assertIndexOnly(plan);
        assertThat(plan).contains("Sort");
    }
}
