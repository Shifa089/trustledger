package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FR-L-4: runs {@link ReconciliationService} nightly ({@code trustledger.reconciliation.cron}) and on demand
 * ({@code POST /admin/reconciliation/run}). Both take the same ShedLock lock (ADR 0013), so across all
 * instances at most one run is in progress.
 */
@Component
public class ReconciliationJob {

    public static final String LOCK_NAME = "wallet-reconciliation";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;
    private final LockingTaskExecutor locks;
    private final ReconciliationProperties properties;
    private final Clock clock;

    public ReconciliationJob(ReconciliationService service, LockingTaskExecutor locks,
            ReconciliationProperties properties, Clock clock) {
        this.service = service;
        this.locks = locks;
        this.properties = properties;
        this.clock = clock;
    }

    /** The nightly run. If another instance holds the lock, this one skips quietly. */
    @Scheduled(cron = "${trustledger.reconciliation.cron}", zone = "${trustledger.reconciliation.zone}")
    public void nightly() {
        if (runLocked(properties.lockAtLeastFor()).isEmpty()) {
            log.info("Nightly reconciliation skipped: another instance holds the lock");
        }
    }

    /**
     * A manual run. It keeps the lock only while it runs, so an admin can run it again right after.
     *
     * @throws TrustLedgerException {@code REQUEST_IN_PROGRESS} if a run is already in progress
     */
    public ReconciliationReport runNow() {
        return runLocked(Duration.ZERO).orElseThrow(() -> new TrustLedgerException(ErrorCode.REQUEST_IN_PROGRESS,
                "A reconciliation run is already in progress."));
    }

    /** Empty when the lock is held elsewhere. */
    Optional<ReconciliationReport> runLocked(Duration lockAtLeastFor) {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, properties.lockAtMostFor(),
                lockAtLeastFor);
        TaskResult<ReconciliationReport> result;
        try {
            result = locks.executeWithLock(service::reconcile, lock);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Reconciliation failed", t);
        }
        return result.wasExecuted() ? Optional.of(result.getResult()) : Optional.empty();
    }
}
