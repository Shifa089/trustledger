package com.trustledger.core.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.application.WalletComplianceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link WalletApi} against PostgreSQL: the lock order, the "lock first" contract, persistence of every
 * movement, frozen-wallet refusals, and no lost update or deadlock under concurrency.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WalletApiIT {

    @Autowired
    WalletApi api;

    @Autowired
    WalletFixtures fixtures;

    @Autowired
    WalletComplianceService compliance;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    private static Money inr(long minor) {
        return Money.of(minor, Money.INR);
    }

    private long balance(UUID walletId) {
        return jdbc.queryForObject("select balance_minor from wallet.wallets where id = ?", Long.class, walletId);
    }

    private long held(UUID walletId) {
        return jdbc.queryForObject("select held_minor from wallet.wallets where id = ?", Long.class, walletId);
    }

    private static ErrorCode code(Throwable e) {
        return ((TrustLedgerException) e).errorCode();
    }

    @Test
    void lockOrderIsPostgresOrderNotJavaUuidOrder() {
        // Java compares the most significant bits as a SIGNED long, so 8000... sorts before 1000...;
        // PostgreSQL compares bytes unsigned, so 1000... comes first. The lock order must be PostgreSQL's.
        long low = ThreadLocalRandom.current().nextLong();
        UUID small = new UUID(0x1000_0000_0000_7000L, low);
        UUID large = new UUID(0x8000_0000_0000_7000L, low);
        insertRawWallet(small);
        insertRawWallet(large);
        assertThat(List.of(small, large).stream().sorted().toList()).containsExactly(large, small);

        List<WalletView> locked = tx.execute(status -> api.lockForUpdate(List.of(large, small, large)));

        assertThat(locked).extracting(WalletView::walletId).containsExactly(small, large);
    }

    /**
     * Regression (M6 part 2): a wallet read unlocked earlier in the transaction (as a transfer resolves its
     * recipient), changed meanwhile by another transaction, must come back from the lock with the committed
     * balance. Before the fix Hibernate refused the newer row version (ObjectOptimisticLockingFailureException).
     */
    @Test
    void lockAfterAnUnlockedReadSeesTheLatestCommittedBalance() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);
        fixtures.fund(wallet, 1_000);

        WalletView afterDebit = tx.execute(status -> {
            assertThat(api.findById(wallet).orElseThrow().balance()).isEqualTo(inr(1_000));
            // Another thread, so another transaction, commits a credit while ours holds the stale snapshot.
            java.util.concurrent.CompletableFuture.runAsync(() -> fixtures.fund(wallet, 500)).join();

            WalletView locked = api.lockForUpdate(List.of(wallet)).getFirst();
            assertThat(locked.balance()).isEqualTo(inr(1_500));
            return api.debit(wallet, inr(1_200));
        });

        assertThat(afterDebit.balance()).isEqualTo(inr(300));
        assertThat(balance(wallet)).isEqualTo(300);
    }

    @Test
    void lockForUpdateNeedsATransaction() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);

        assertThatThrownBy(() -> api.lockForUpdate(List.of(wallet)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> api.credit(wallet, inr(1))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void unknownWalletIsNotFound() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);

        assertThatThrownBy(() -> tx.execute(s -> api.lockForUpdate(List.of(wallet, UUID.randomUUID()))))
                .isInstanceOf(TrustLedgerException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void movingMoneyWithoutTheLockIsRefusedAndNothingIsWritten() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);

        assertThatIllegalStateException().isThrownBy(() -> tx.execute(s -> api.credit(wallet, inr(100))))
                .withMessageContaining("lockForUpdate");

        assertThat(balance(wallet)).isZero();
    }

    @Test
    void walletReadEarlierInTheTransactionCanStillBeLocked() {
        UUID user = fixtures.user();
        UUID wallet = fixtures.wallet(user, WalletTier.FULL);

        WalletView after = tx.execute(s -> {
            api.findByUser(user); // loaded without a lock first
            api.lockForUpdate(List.of(wallet));
            return api.credit(wallet, inr(500));
        });

        assertThat(after.balance()).isEqualTo(inr(500));
        assertThat(balance(wallet)).isEqualTo(500);
    }

    @Test
    void debitCreditHoldAndReleaseArePersisted() {
        UUID from = fixtures.wallet(WalletTier.FULL);
        UUID to = fixtures.wallet(WalletTier.FULL);
        fixtures.fund(from, 100_000);

        tx.executeWithoutResult(s -> {
            api.lockForUpdate(List.of(from, to));
            api.debit(from, inr(30_000));
            api.credit(to, inr(30_000));
            api.hold(from, inr(50_000));
            api.releaseHold(from, inr(10_000));
        });

        assertThat(balance(from)).isEqualTo(70_000);
        assertThat(held(from)).isEqualTo(40_000);
        assertThat(balance(to)).isEqualTo(30_000);
        assertThat(balance(from) - held(from)).isEqualTo(30_000); // available
    }

    @Test
    void refusedMovementRollsBackTheWholeTransaction() {
        UUID from = fixtures.wallet(WalletTier.FULL);
        UUID to = fixtures.wallet(WalletTier.FULL);
        fixtures.fund(from, 1_000);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            api.lockForUpdate(List.of(from, to));
            api.credit(to, inr(5_000));
            api.debit(from, inr(5_000)); // more than available
        })).satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        assertThat(balance(from)).isEqualTo(1_000);
        assertThat(balance(to)).isZero();
    }

    @Test
    void frozenWalletCannotSendOrReceiveButCanReleaseAHold() {
        UUID wallet = fixtures.wallet(WalletTier.FULL);
        fixtures.fund(wallet, 10_000);
        fixtures.hold(wallet, 4_000);
        compliance.freeze(wallet, fixtures.user(), "Under investigation");

        for (Supplier<WalletView> movement : List.<Supplier<WalletView>>of(
                () -> api.debit(wallet, inr(1)), () -> api.credit(wallet, inr(1)), () -> api.hold(wallet, inr(1)))) {
            assertThatThrownBy(() -> tx.execute(s -> {
                api.lockForUpdate(List.of(wallet));
                return movement.get();
            })).satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.WALLET_FROZEN));
        }

        tx.executeWithoutResult(s -> {
            api.lockForUpdate(List.of(wallet));
            api.releaseHold(wallet, inr(4_000));
        });
        assertThat(balance(wallet)).isEqualTo(10_000);
        assertThat(held(wallet)).isZero();
    }

    @Test
    void limitsFollowTheTier() {
        UUID min = fixtures.wallet(WalletTier.MIN);
        UUID full = fixtures.wallet(WalletTier.FULL);

        assertThat(api.limitsFor(min).maxBalance()).isEqualTo(Money.parse("10000.00"));
        assertThat(api.limitsFor(min).p2pAllowed()).isFalse();
        assertThat(api.limitsFor(full).maxBalance()).isEqualTo(Money.parse("200000.00"));
        assertThat(api.limitsFor(full).dailyTransfer()).isEqualTo(Money.parse("100000.00"));
        assertThat(api.limitsFor(full).highValueThreshold()).isEqualTo(Money.parse("50000.00"));
    }

    @Test
    void concurrentCreditsAndDebitsLoseNoUpdate() throws Exception {
        UUID wallet = fixtures.wallet(WalletTier.FULL);
        fixtures.fund(wallet, 1_000_000);
        int threads = 50;

        runConcurrently(threads, i -> tx.executeWithoutResult(s -> {
            api.lockForUpdate(List.of(wallet));
            if (i % 2 == 0) {
                api.credit(wallet, inr(300));
            } else {
                api.debit(wallet, inr(100));
            }
        }));

        assertThat(balance(wallet)).isEqualTo(1_000_000 + 25 * 300 - 25 * 100);
    }

    @Test
    void oppositeTransfersNeverDeadlock() throws Exception {
        UUID a = fixtures.wallet(WalletTier.FULL);
        UUID b = fixtures.wallet(WalletTier.FULL);
        fixtures.fund(a, 1_000_000);
        fixtures.fund(b, 1_000_000);
        int threads = 100;

        // Half move a -> b and ask for [a, b]; half move b -> a and ask for [b, a]. Locking in the order asked
        // would deadlock; lockForUpdate always locks in ascending ID order.
        runConcurrently(threads, i -> {
            UUID from = i % 2 == 0 ? a : b;
            UUID to = i % 2 == 0 ? b : a;
            tx.executeWithoutResult(s -> {
                api.lockForUpdate(List.of(from, to));
                api.debit(from, inr(1_000 + i));
                api.credit(to, inr(1_000 + i));
            });
        });

        long expectedA = 1_000_000;
        for (int i = 0; i < threads; i++) {
            expectedA += i % 2 == 0 ? -(1_000 + i) : 1_000 + i;
        }
        assertThat(balance(a)).isEqualTo(expectedA);
        assertThat(balance(a) + balance(b)).isEqualTo(2_000_000); // money is neither created nor lost
    }

    private interface Task {
        void run(int index);
    }

    /** Starts every task at the same moment; fails on the first exception (a deadlock would surface here). */
    private static void runConcurrently(int count, Task task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
    }

    private void insertRawWallet(UUID id) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into wallet.wallets (id, user_id, tier, status, balance_minor, held_minor, currency,
                    created_at, updated_at, version) values (?, ?, 'FULL', 'ACTIVE', 0, 0, 'INR', ?, ?, 0)""",
                id, UUID.randomUUID(), now, now);
    }
}
