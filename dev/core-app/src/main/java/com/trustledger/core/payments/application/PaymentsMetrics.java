package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Business metrics of the payments module (M12, SPEC §8). Names are Micrometer's; over OTLP into Prometheus they
 * become {@code trustledger_transfers_total{status}}, {@code trustledger_transfer_amount_bucket/_sum/_count} and
 * {@code trustledger_topups_total{status}}.
 * <ul>
 * <li>Counts are <b>state changes</b>: a held transfer counts once as {@code HELD_FOR_REVIEW} and once more when an
 * officer approves ({@code COMPLETED}) or rejects ({@code REJECTED}) it. A transfer refused by a rule (nothing is
 * saved) counts as {@code FAILED}.</li>
 * <li>Inside a transaction a state change is counted <b>after the commit</b>, so a rolled-back transfer is never
 * counted as completed. An idempotent replay doesn't reach the services, so it isn't counted again.</li>
 * <li>Tags are enum names only: few series, never personal data.</li>
 * </ul>
 */
@Component
public class PaymentsMetrics {

    public static final String TRANSFERS = "trustledger.transfers";
    public static final String TRANSFER_AMOUNT = "trustledger.transfer.amount";
    public static final String TOPUPS = "trustledger.topups";

    /** Bucket bounds in rupees; 45k-50k is the structuring band of AML rule R2, 1L its large-amount rule R3. */
    static final double[] AMOUNT_BUCKETS_RUPEES = {100, 1_000, 5_000, 10_000, 45_000, 50_000, 100_000, 200_000};

    private final Map<TransferStatus, Counter> transfers = new EnumMap<>(TransferStatus.class);
    private final Map<TopupStatus, Counter> topups = new EnumMap<>(TopupStatus.class);
    private final DistributionSummary transferAmount;

    public PaymentsMetrics(MeterRegistry meters) {
        for (TransferStatus status : TransferStatus.values()) {
            transfers.put(status, Counter.builder(TRANSFERS)
                    .description("P2P transfers per state change (FAILED: refused by a rule, nothing saved)")
                    .tag("status", status.name())
                    .register(meters));
        }
        for (TopupStatus status : TopupStatus.values()) {
            topups.put(status, Counter.builder(TOPUPS)
                    .description("Top-ups per state change")
                    .tag("status", status.name())
                    .register(meters));
        }
        // No base unit on purpose: Prometheus would append it to the name (trustledger_transfer_amount_INR_...).
        transferAmount = DistributionSummary.builder(TRANSFER_AMOUNT)
                .description("Amount in rupees of each transfer that was completed or held for review")
                .serviceLevelObjectives(AMOUNT_BUCKETS_RUPEES)
                .register(meters);
    }

    /** A new transfer was completed or held: counts it and records its amount, after the commit. */
    void transferAccepted(TransferStatus status, Money amount) {
        afterCommit(() -> {
            transfers.get(status).increment();
            // Metrics only, never money arithmetic: paise to rupees as a double is fine for a histogram.
            transferAmount.record(amount.amountMinor() / 100.0);
        });
    }

    /** A held transfer was approved ({@code COMPLETED}) or rejected ({@code REJECTED}), after the commit. */
    void transferReviewed(TransferStatus status) {
        afterCommit(() -> transfers.get(status).increment());
    }

    /** A rule refused the transfer. Counted at once: the rollback is the expected result, not a reason to skip. */
    void transferRefused() {
        transfers.get(TransferStatus.FAILED).increment();
    }

    /** A top-up reached {@code status}; after the commit when a transaction is running. */
    void topup(TopupStatus status) {
        afterCommit(() -> topups.get(status).increment());
    }

    private static void afterCommit(Runnable count) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            count.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                count.run();
            }
        });
    }
}
