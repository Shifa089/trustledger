package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletTier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * M6 "Done when" 2 and 3: the ordered {@code SELECT ... FOR UPDATE} locks under real contention.
 * <p>
 * All tasks wait on one {@link CountDownLatch} so they hit the database together, instead of trickling in as
 * the pool starts threads. Outcomes are the service's own answers: a success, or a {@link TrustLedgerException}
 * with a business code. Anything else (a deadlock, a lock timeout, an optimistic-lock failure) fails the test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferConcurrencyIT {

    private static final int THREADS = 16;

    @Autowired
    TransferService service;

    @Autowired
    PaymentsFixtures fixtures;

    private enum Outcome { COMPLETED, INSUFFICIENT_FUNDS }

    private Callable<Outcome> transfer(Customer from, Customer to, long minor, CountDownLatch start) {
        TransferCommand command = new TransferCommand(from.userId(), TransferRecipient.byWalletId(to.walletId()),
                Money.of(minor, Money.INR), null);
        return () -> {
            start.await();
            try {
                service.transfer(command);
                return Outcome.COMPLETED;
            } catch (TrustLedgerException e) {
                if (e.errorCode() == ErrorCode.INSUFFICIENT_FUNDS) {
                    return Outcome.INSUFFICIENT_FUNDS;
                }
                throw e;
            }
        };
    }

    /**
     * Submits every task, then opens {@code start} so they all go at once; returns their outcomes. An unexpected
     * exception fails the test.
     */
    private static List<Outcome> runTogether(List<Callable<Outcome>> tasks, CountDownLatch start)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void hundredConcurrentTransfersWithBalanceForFiftyExactlyFiftySucceed() throws Exception {
        Customer sender = fixtures.customer(WalletTier.FULL, 500_000); // ₹5,000 = 50 x ₹100
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(transfer(sender, receiver, 10_000, start));
        }

        List<Outcome> outcomes = runTogether(tasks, start);

        assertThat(outcomes).filteredOn(o -> o == Outcome.COMPLETED).hasSize(50);
        assertThat(outcomes).filteredOn(o -> o == Outcome.INSUFFICIENT_FUNDS).hasSize(50);
        assertThat(fixtures.wallet(sender.walletId()).balance().isZero()).as("never negative, ends at 0").isTrue();
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(Money.of(500_000, Money.INR));
        assertThat(fixtures.transfersFrom(sender.walletId())).isEqualTo(50);
        // The ledger reconciles: the journal-derived balances equal the cached ones.
        assertThat(fixtures.ledgerBalance(sender.walletId())).isZero();
        assertThat(fixtures.ledgerBalance(receiver.walletId())).isEqualTo(500_000);
    }

    @Test
    void oppositeTransfersTwoHundredTimesNeverDeadlockAndPreserveTotals() throws Exception {
        Customer a = fixtures.customer(WalletTier.FULL, 1_000_000);
        Customer b = fixtures.customer(WalletTier.FULL, 1_000_000);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(i % 2 == 0 ? transfer(a, b, 100, start) : transfer(b, a, 100, start));
        }

        List<Outcome> outcomes = runTogether(tasks, start);

        assertThat(outcomes).as("no deadlock: every transfer completed").containsOnly(Outcome.COMPLETED).hasSize(200);
        // 100 each way of ₹1: both end where they started, and the total is preserved.
        assertThat(fixtures.wallet(a.walletId()).balance()).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(fixtures.wallet(b.walletId()).balance()).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(fixtures.ledgerBalance(a.walletId()) + fixtures.ledgerBalance(b.walletId())).isEqualTo(2_000_000);
        assertThat(fixtures.ledgerBalance(a.walletId())).isEqualTo(1_000_000);
    }
}
