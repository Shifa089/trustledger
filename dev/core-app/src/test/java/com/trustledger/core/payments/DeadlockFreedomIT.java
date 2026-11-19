package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M6 "Done when" 3 / must-pass 5: transfers in opposite directions never deadlock, because every transaction
 * locks wallets in the same (ascending ID) order (ADR 0004).
 * <p>
 * PostgreSQL detects a deadlock after {@code deadlock_timeout} (1 s by default) and aborts one transaction with
 * SQLSTATE {@code 40P01}. So a deadlock shows up as an exception, which this test classifies as
 * {@link Outcome#DEADLOCK} and counts. A hang that the database can't see would hit {@link ConcurrentRun}'s
 * 60 s timeout instead.
 * <p>
 * The control test locks the same two rows in <em>opposite</em> orders on purpose and expects exactly one
 * {@code 40P01}. It proves the detection works, so a green main test can't just mean the deadlock went unnoticed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadlockFreedomIT {

    private static final String DEADLOCK_DETECTED = "40P01";

    @Autowired
    TransferService service;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private enum Outcome { COMPLETED, DEADLOCK }

    /** A deadlock as Spring translates it, or a raw SQLSTATE 40P01 anywhere in the cause chain. */
    private static boolean isDeadlock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (t instanceof SQLException sql && DEADLOCK_DETECTED.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private Callable<Outcome> transfer(Customer from, Customer to, long minor) {
        TransferCommand command = new TransferCommand(from.userId(), TransferRecipient.byWalletId(to.walletId()),
                Money.of(minor, Money.INR), null);
        return () -> {
            try {
                service.transfer(command);
                return Outcome.COMPLETED;
            } catch (RuntimeException e) {
                if (isDeadlock(e)) {
                    return Outcome.DEADLOCK;
                }
                throw e;
            }
        };
    }

    @Test
    void oppositeTransfersTwoHundredTimesNeverDeadlockAndPreserveTotals() throws Exception {
        Customer a = fixtures.customer(WalletTier.FULL, 1_000_000);
        Customer b = fixtures.customer(WalletTier.FULL, 1_000_000);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(i % 2 == 0 ? transfer(a, b, 100) : transfer(b, a, 100));
        }

        List<Outcome> outcomes = ConcurrentRun.together(tasks);

        assertThat(outcomes).as("deadlocks detected by PostgreSQL").filteredOn(o -> o == Outcome.DEADLOCK)
                .isEmpty();
        assertThat(outcomes).as("every transfer completed").containsOnly(Outcome.COMPLETED).hasSize(200);
        // 100 each way of ₹1: both end where they started, and the total is preserved in cache and ledger.
        assertThat(fixtures.wallet(a.walletId()).balance()).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(fixtures.wallet(b.walletId()).balance()).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(fixtures.ledgerBalance(a.walletId())).isEqualTo(1_000_000);
        assertThat(fixtures.ledgerBalance(b.walletId())).isEqualTo(1_000_000);
    }

    /** Control: the wrong order (each transaction locks "its own" wallet first) does deadlock, and we see it. */
    @Test
    void controlLockingInOppositeOrdersIsDetectedAsADeadlock() throws Exception {
        UUID a = fixtures.customer(WalletTier.FULL, 0).walletId();
        UUID b = fixtures.customer(WalletTier.FULL, 0).walletId();
        CountDownLatch bothHoldFirstLock = new CountDownLatch(2);

        List<Outcome> outcomes = ConcurrentRun.together(List.of(
                lockInOrder(a, b, bothHoldFirstLock),
                lockInOrder(b, a, bothHoldFirstLock)));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.COMPLETED, Outcome.DEADLOCK);
    }

    private Callable<Outcome> lockInOrder(UUID first, UUID second, CountDownLatch bothHoldFirstLock) {
        String lock = "select id from wallet.wallets where id = ? for update";
        return () -> {
            try {
                tx.executeWithoutResult(status -> {
                    jdbc.queryForObject(lock, UUID.class, first);
                    bothHoldFirstLock.countDown();
                    try {
                        // Both transactions now hold one lock each; each next asks for the other's.
                        if (!bothHoldFirstLock.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("the other transaction never took its first lock");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    jdbc.queryForObject(lock, UUID.class, second);
                });
                return Outcome.COMPLETED;
            } catch (RuntimeException e) {
                if (isDeadlock(e)) {
                    return Outcome.DEADLOCK;
                }
                throw e;
            }
        };
    }
}
