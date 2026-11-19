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
 * Deletes expired idempotency keys ({@code trustledger.payments.idempotency.cleanup-cron}, hourly) in batches,
 * each batch its own short transaction. ShedLock (ADR 0013) keeps it to one instance at a time.
 * <p>
 * Correctness doesn't depend on this job: an expired key can be claimed again anyway. It only keeps the table
 * small.
 */
@Component
public class IdempotencyCleanupJob {

    public static final String LOCK_NAME = "payments-idempotency-cleanup";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService service;
    private final LockingTaskExecutor locks;
    private final PaymentsProperties properties;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyService service, LockingTaskExecutor locks,
            PaymentsProperties properties, Clock clock) {
        this.service = service;
        this.locks = locks;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${trustledger.payments.idempotency.cleanup-cron}")
    public void scheduled() {
        runLocked().ifPresentOrElse(
                deleted -> log.info("Idempotency cleanup deleted {} expired keys", deleted),
                () -> log.info("Idempotency cleanup skipped: another instance holds the lock"));
    }

    /** @return the number of deleted keys, or empty if another instance holds the lock */
    public Optional<Integer> runLocked() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME,
                properties.idempotency().lockAtMostFor(), Duration.ZERO);
        TaskResult<Integer> result;
        try {
            result = locks.executeWithLock(this::purge, lock);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Idempotency cleanup failed", t);
        }
        return result.wasExecuted() ? Optional.of(result.getResult()) : Optional.empty();
    }

    private int purge() {
        int batch = properties.idempotency().cleanupBatchSize();
        int total = 0;
        int deleted;
        do {
            deleted = service.deleteExpiredBatch(batch);
            total += deleted;
        } while (deleted == batch);
        return total;
    }
}
