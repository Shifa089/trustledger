package com.trustledger.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.LedgerSql.Line;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FR-L-3 through {@link LedgerApi#reverse}. The original entries are written with raw SQL, so only the reversal
 * itself goes through {@code LedgerPostingService.post()}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerReversalIT {

    @Autowired
    LedgerApi ledger;

    @Autowired
    LedgerSql sql;

    @Autowired
    JdbcTemplate jdbc;

    private static void assertCode(ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    private String walletAccount() {
        UUID walletId = UUID.randomUUID();
        ledger.openWalletAccount(walletId, Money.INR);
        return AccountCodes.wallet(walletId);
    }

    private UUID topup(String walletCode, long minor) {
        return sql.post(Line.debit(sql.accountId(AccountCodes.GATEWAY_CLEARING), minor),
                Line.credit(sql.accountId(walletCode), minor));
    }

    @Test
    void reversalFlipsEveryLineAndNetsTheBalanceToZero() {
        String wallet = walletAccount();
        UUID original = topup(wallet, 25_000);
        assertThat(ledger.balanceOf(wallet).balanceMinor()).isEqualTo(25_000);

        UUID reversal = ledger.reverse(original, "Captured twice by the gateway");

        Map<String, Object> entry = jdbc.queryForMap("""
                select entry_type, reference_type, reference_id, reverses_entry_id, reason
                from ledger.journal_entries where id = ?""", reversal);
        assertThat(entry).containsEntry("entry_type", "REVERSAL").containsEntry("reference_type", "JOURNAL_ENTRY")
                .containsEntry("reference_id", original).containsEntry("reverses_entry_id", original)
                .containsEntry("reason", "Captured twice by the gateway");
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select a.code, l.direction, l.amount_minor from ledger.journal_lines l
                join ledger.ledger_accounts a on a.id = l.account_id where l.entry_id = ? order by l.line_no""",
                reversal);
        assertThat(lines).extracting(l -> l.get("code")).containsExactly(AccountCodes.GATEWAY_CLEARING, wallet);
        assertThat(lines).extracting(l -> l.get("direction")).containsExactly("CREDIT", "DEBIT");
        assertThat(lines).extracting(l -> l.get("amount_minor")).containsExactly(25_000L, 25_000L);
        assertThat(ledger.balanceOf(wallet).balanceMinor()).isZero();
        assertThat(sql.entryExists(original)).isTrue(); // the original is never touched
    }

    @Test
    void secondReversalIsADuplicate() {
        String wallet = walletAccount();
        UUID original = topup(wallet, 1_000);
        ledger.reverse(original, "First correction");

        assertCode(() -> ledger.reverse(original, "Second correction"), ErrorCode.DUPLICATE_POSTING);

        assertThat(jdbc.queryForObject("select count(*) from ledger.journal_entries where reverses_entry_id = ?",
                Integer.class, original)).isEqualTo(1);
        assertThat(ledger.balanceOf(wallet).balanceMinor()).isZero();
    }

    /**
     * Two admins reverse the same entry at the same moment: all pass the "already reversed?" check, and the
     * unique reversal keys let exactly one insert win. The others get DUPLICATE_POSTING, not a raw SQL error.
     */
    @Test
    void concurrentReversalsOfTheSameEntryReverseItExactlyOnce() throws Exception {
        String wallet = walletAccount();
        UUID original = topup(wallet, 9_000);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return ledger.reverse(original, "Correction " + n);
                    } catch (TrustLedgerException e) {
                        return e.errorCode();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(UUID.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ErrorCode.DUPLICATE_POSTING::equals).hasSize(threads - 1);
        }
        assertThat(jdbc.queryForObject("select count(*) from ledger.journal_entries where reverses_entry_id = ?",
                Integer.class, original)).isEqualTo(1);
        assertThat(ledger.balanceOf(wallet).balanceMinor()).isZero();
    }

    @Test
    void multiLineEntryIsReversedLineByLineInTheSameOrder() {
        String a = walletAccount();
        String b = walletAccount();
        UUID original = sql.post(Line.debit(sql.accountId(AccountCodes.GATEWAY_CLEARING), 500),
                Line.credit(sql.accountId(a), 200), Line.credit(sql.accountId(b), 300));

        UUID reversal = ledger.reverse(original, "Split posted to the wrong wallets");

        List<Map<String, Object>> lines = jdbc.queryForList("""
                select a.code, l.direction, l.amount_minor, l.line_no from ledger.journal_lines l
                join ledger.ledger_accounts a on a.id = l.account_id where l.entry_id = ? order by l.line_no""",
                reversal);
        assertThat(lines).extracting(l -> l.get("code")).containsExactly(AccountCodes.GATEWAY_CLEARING, a, b);
        assertThat(lines).extracting(l -> l.get("direction")).containsExactly("CREDIT", "DEBIT", "DEBIT");
        assertThat(lines).extracting(l -> l.get("amount_minor")).containsExactly(500L, 200L, 300L);
        assertThat(lines).extracting(l -> l.get("line_no")).containsExactly(1, 2, 3);
        assertThat(ledger.balanceOf(a).balanceMinor()).isZero();
        assertThat(ledger.balanceOf(b).balanceMinor()).isZero();
    }

    /** The debit-normal platform asset goes back to exactly where it was, like the credit-normal wallet. */
    @Test
    void reversalRestoresThePlatformAccountToo() {
        long clearingBefore = ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor();
        UUID original = topup(walletAccount(), 12_345);
        assertThat(ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor())
                .isEqualTo(clearingBefore + 12_345);

        ledger.reverse(original, "Gateway refunded the payment");

        assertThat(ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor()).isEqualTo(clearingBefore);
    }

    @Test
    void reversalCannotBeReversed() {
        String wallet = walletAccount();
        UUID clearing = sql.accountId(AccountCodes.GATEWAY_CLEARING);
        UUID walletId = sql.accountId(wallet);
        UUID original = sql.post(Line.debit(clearing, 300), Line.credit(walletId, 300));
        UUID reversal = UUID.randomUUID();
        sql.post(reversal, "REVERSAL", original, original, "raw reversal", Line.credit(clearing, 300),
                Line.debit(walletId, 300));

        assertCode(() -> ledger.reverse(reversal, "Undo the undo"), ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void unknownEntryIsNotFound() {
        assertCode(() -> ledger.reverse(UUID.randomUUID(), "why"), ErrorCode.NOT_FOUND);
    }

    @Test
    void reasonIsRequired() {
        UUID original = topup(walletAccount(), 100);

        assertCode(() -> ledger.reverse(original, " "), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void postRefusesAHandMadeReversal() {
        UUID original = topup(walletAccount(), 100);
        PostingCommand handMade = PostingCommand.reversal(original, "why", List.of(
                PostingLine.credit(AccountCodes.GATEWAY_CLEARING, Money.of(100, Money.INR)),
                PostingLine.debit(AccountCodes.SUSPENSE, Money.of(100, Money.INR))));

        assertCode(() -> ledger.post(handMade), ErrorCode.VALIDATION_FAILED);
    }
}
