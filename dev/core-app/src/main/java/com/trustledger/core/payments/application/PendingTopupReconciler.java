package com.trustledger.core.payments.application;

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
 * FR-P-6: runs {@link TopupReconciliationService} every 5 minutes ({@code trustledger.payments.reconciler.cron}).
 * ShedLock (ADR 0013) keeps it to one instance at a time. Correctness doesn't depend on the lock: settling locks the
 * top-up row, so even two overlapping runs credit a top-up once. The lock only avoids asking the gateway twice.
 */
@Component
public class PendingTopupReconciler {

    public static final String LOCK_NAME = "payments-topup-reconciler";

    private static final Logger log = LoggerFactory.getLogger(PendingTopupReconciler.class);

    private final TopupReconciliationService service;
    private final LockingTaskExecutor locks;
    private final TopupReconcilerProperties properties;
    private final Clock clock;

    public PendingTopupReconciler(TopupReconciliationService service, LockingTaskExecutor locks,
            TopupReconcilerProperties properties, Clock clock) {
        this.service = service;
        this.locks = locks;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${trustledger.payments.reconciler.cron}")
    public void scheduled() {
        if (runLocked().isEmpty()) {
            log.info("Pending top-up reconciliation skipped: another instance holds the lock");
        }
    }

    /** @return the run's report, or empty if another instance holds the lock */
    public Optional<TopupReconciliationReport> runLocked() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, properties.lockAtMostFor(),
                Duration.ZERO);
        TaskResult<TopupReconciliationReport> result;
        try {
            result = locks.executeWithLock(service::reconcile, lock);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Pending top-up reconciliation failed", t);
        }
        return result.wasExecuted() ? Optional.of(result.getResult()) : Optional.empty();
    }
}
