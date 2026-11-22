package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PaymentsMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PaymentsMetrics metrics = new PaymentsMetrics(meters);

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** What a real @Transactional method sets up: an actual transaction with synchronization. */
    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @Test
    void everyStatusIsRegisteredAtZero() {
        for (TransferStatus status : TransferStatus.values()) {
            assertThat(transfers(status)).isZero();
        }
        for (TopupStatus status : TopupStatus.values()) {
            assertThat(meters.get(PaymentsMetrics.TOPUPS).tag("status", status.name()).counter().count()).isZero();
        }
    }

    @Test
    void outsideATransactionItCountsAtOnce() {
        metrics.transferAccepted(TransferStatus.COMPLETED, Money.parse("1500.50"));
        metrics.topup(TopupStatus.PENDING);

        assertThat(transfers(TransferStatus.COMPLETED)).isEqualTo(1.0);
        assertThat(meters.get(PaymentsMetrics.TOPUPS).tag("status", "PENDING").counter().count()).isEqualTo(1.0);
        assertThat(meters.get(PaymentsMetrics.TRANSFER_AMOUNT).summary().totalAmount()).isEqualTo(1500.50);
    }

    @Test
    void insideATransactionItCountsOnlyAfterTheCommit() {
        beginTransaction();
        metrics.transferAccepted(TransferStatus.HELD_FOR_REVIEW, Money.parse("50000.00"));
        assertThat(transfers(TransferStatus.HELD_FOR_REVIEW)).as("not before the commit").isZero();

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);

        assertThat(transfers(TransferStatus.HELD_FOR_REVIEW)).isEqualTo(1.0);
        assertThat(meters.get(PaymentsMetrics.TRANSFER_AMOUNT).summary().count()).isEqualTo(1);
    }

    @Test
    void aRolledBackTransactionCountsNothing() {
        beginTransaction();
        metrics.transferAccepted(TransferStatus.COMPLETED, Money.parse("10.00"));
        metrics.topup(TopupStatus.CAPTURED);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(transfers(TransferStatus.COMPLETED)).isZero();
        assertThat(meters.get(PaymentsMetrics.TRANSFER_AMOUNT).summary().count()).isZero();
    }

    @Test
    void aRefusalCountsAsFailedEvenThoughItsTransactionRollsBack() {
        beginTransaction();

        metrics.transferRefused();

        assertThat(transfers(TransferStatus.FAILED)).isEqualTo(1.0);
    }

    @Test
    void synchronizationWithoutARealTransactionCountsAtOnce() {
        // Propagation.NEVER (TopupService.create) still activates synchronization for its "empty" transaction, and
        // when the method throws there is no afterCommit. Found by TopupMetricsIT: the FAILED top-up went uncounted.
        TransactionSynchronizationManager.initSynchronization();

        metrics.topup(TopupStatus.FAILED);

        assertThat(meters.get(PaymentsMetrics.TOPUPS).tag("status", "FAILED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void amountsFallIntoRupeeBuckets() {
        metrics.transferAccepted(TransferStatus.COMPLETED, Money.parse("46000.00")); // the R2 structuring band

        CountAtBucket[] buckets = meters.get(PaymentsMetrics.TRANSFER_AMOUNT).summary().takeSnapshot()
                .histogramCounts();
        assertThat(buckets).extracting(CountAtBucket::bucket)
                .containsExactly(100.0, 1_000.0, 5_000.0, 10_000.0, 45_000.0, 50_000.0, 100_000.0, 200_000.0);
        assertThat(buckets).filteredOn(bucket -> bucket.bucket() == 45_000.0).singleElement()
                .extracting(CountAtBucket::count).isEqualTo(0.0);
        assertThat(buckets).filteredOn(bucket -> bucket.bucket() == 50_000.0).singleElement()
                .extracting(CountAtBucket::count).isEqualTo(1.0);
    }

    private double transfers(TransferStatus status) {
        return meters.get(PaymentsMetrics.TRANSFERS).tag("status", status.name()).counter().count();
    }
}
