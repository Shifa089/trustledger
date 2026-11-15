package com.trustledger.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.application.LedgerPostingService;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.ArrayList;
import java.util.Currency;
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
 * The contract of {@link LedgerPostingService#post} (FR-L-1, SPEC §4, M5). <b>These tests fail until
 * {@code post()} is written</b>: today it throws {@link UnsupportedOperationException}.
 * <p>
 * Every test uses fresh wallet accounts and a fresh reference, so tests don't see each other's postings.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerPostingServiceIT {

    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    LedgerPostingService posting;

    @Autowired
    LedgerApi ledger;

    @Autowired
    JdbcTemplate jdbc;

    private static Money inr(long minor) {
        return Money.of(minor, Money.INR);
    }

    /** A new {@code WALLET:<id>} account, opened the way the wallet module opens it. */
    private String walletAccount() {
        UUID walletId = UUID.randomUUID();
        ledger.openWalletAccount(walletId, Money.INR);
        return AccountCodes.wallet(walletId);
    }

    private static PostingCommand topup(UUID reference, String walletCode, long minor) {
        return PostingCommand.of(EntryType.TOPUP, "TOPUP", reference, List.of(
                PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(minor)),
                PostingLine.credit(walletCode, inr(minor))));
    }

    private static PostingCommand command(PostingLine... lines) {
        return PostingCommand.of(EntryType.P2P_TRANSFER, "TRANSFER", UUID.randomUUID(), List.of(lines));
    }

    private int entriesFor(UUID reference) {
        return jdbc.queryForObject("select count(*) from ledger.journal_entries where reference_id = ?",
                Integer.class, reference);
    }

    /** Refused with this code, and nothing was written for the command's reference. */
    private void assertRejected(PostingCommand command, ErrorCode code) {
        ThrowingCallable post = () -> posting.post(command);
        assertThatThrownBy(post).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
        assertThat(entriesFor(command.referenceId())).isZero();
    }

    @Test
    void balancedEntryIsSavedWithItsLines() {
        String wallet = walletAccount();
        UUID reference = UUID.randomUUID();

        UUID entryId = posting.post(topup(reference, wallet, 150_050));

        Map<String, Object> entry = jdbc.queryForMap(
                "select entry_type, reference_type, reference_id, reverses_entry_id, reason, posted_at "
                        + "from ledger.journal_entries where id = ?", entryId);
        assertThat(entry).containsEntry("entry_type", "TOPUP").containsEntry("reference_type", "TOPUP")
                .containsEntry("reference_id", reference);
        assertThat(entry.get("reverses_entry_id")).isNull();
        assertThat(entry.get("reason")).isNull();
        assertThat(entry.get("posted_at")).isNotNull();
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select a.code, l.direction, l.amount_minor, l.line_no from ledger.journal_lines l
                join ledger.ledger_accounts a on a.id = l.account_id where l.entry_id = ? order by l.line_no""",
                entryId);
        assertThat(lines).extracting(l -> l.get("code")).containsExactly(AccountCodes.GATEWAY_CLEARING, wallet);
        assertThat(lines).extracting(l -> l.get("direction")).containsExactly("DEBIT", "CREDIT");
        assertThat(lines).extracting(l -> l.get("amount_minor")).containsExactly(150_050L, 150_050L);
        assertThat(lines).extracting(l -> l.get("line_no")).containsExactly(1, 2);
        assertThat(ledger.balanceOf(wallet).toMoney()).isEqualTo(inr(150_050));
    }

    @Test
    void transferMovesTheBalanceBetweenWalletAccounts() {
        String sender = walletAccount();
        String receiver = walletAccount();
        posting.post(topup(UUID.randomUUID(), sender, 100_000));

        posting.post(command(PostingLine.debit(sender, inr(40_000)), PostingLine.credit(receiver, inr(40_000))));

        assertThat(ledger.balanceOf(sender).balanceMinor()).isEqualTo(60_000);
        assertThat(ledger.balanceOf(receiver).balanceMinor()).isEqualTo(40_000);
    }

    @Test
    void entryWithMoreThanTwoLinesIsAccepted() {
        String a = walletAccount();
        String b = walletAccount();

        posting.post(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(300)),
                PostingLine.credit(a, inr(100)), PostingLine.credit(b, inr(200))));

        assertThat(ledger.balanceOf(a).balanceMinor()).isEqualTo(100);
        assertThat(ledger.balanceOf(b).balanceMinor()).isEqualTo(200);
    }

    @Test
    void unbalancedEntryIsRejected() {
        String wallet = walletAccount();

        assertRejected(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(100)),
                PostingLine.credit(wallet, inr(99))), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void entryWithOnlyDebitsIsRejected() {
        String wallet = walletAccount();

        assertRejected(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(100)),
                PostingLine.debit(wallet, inr(100))), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void singleLineIsRejected() {
        assertRejected(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(100))),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void entryWithoutLinesIsRejected() {
        assertRejected(command(), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void linesInDifferentCurrenciesAreRejected() {
        String wallet = walletAccount();

        assertRejected(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(100)),
                PostingLine.credit(wallet, Money.of(100, USD))), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void linesInAnotherCurrencyThanTheirAccountsAreRejected() {
        String wallet = walletAccount();

        assertRejected(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, Money.of(100, USD)),
                PostingLine.credit(wallet, Money.of(100, USD))), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void zeroAmountIsRejected() {
        String wallet = walletAccount();

        assertRejected(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(0)),
                PostingLine.credit(wallet, inr(0))), ErrorCode.VALIDATION_FAILED);
    }

    /** A negative amount can't reach post(): Money refuses it, and the database CHECK refuses raw SQL. */
    @Test
    void negativeAmountCannotBeExpressed() {
        assertThatThrownBy(() -> inr(-100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAccountIsRejected() {
        String unknown = AccountCodes.wallet(UUID.randomUUID());

        assertRejected(command(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, inr(100)),
                PostingLine.credit(unknown, inr(100))), ErrorCode.NOT_FOUND);
    }

    @Test
    void duplicateReferenceIsRejected() {
        String wallet = walletAccount();
        UUID reference = UUID.randomUUID();
        posting.post(topup(reference, wallet, 500));

        assertThatThrownBy(() -> posting.post(topup(reference, wallet, 500))).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.DUPLICATE_POSTING);

        assertThat(entriesFor(reference)).isEqualTo(1);
        assertThat(ledger.balanceOf(wallet).balanceMinor()).isEqualTo(500);
    }

    /**
     * Idempotent retry under concurrency: 8 threads post the same business event at the same moment. All of
     * them pass the "already posted?" check before any has committed; the unique reference lets exactly one
     * insert win, and every other one must come back as DUPLICATE_POSTING, not as a raw database error.
     */
    @Test
    void concurrentPostsOfTheSameReferencePostExactlyOnce() throws Exception {
        String wallet = walletAccount();
        UUID reference = UUID.randomUUID();
        PostingCommand command = topup(reference, wallet, 700);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return posting.post(command);
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
        assertThat(entriesFor(reference)).isEqualTo(1);
        assertThat(ledger.balanceOf(wallet).balanceMinor()).isEqualTo(700);
    }
}
