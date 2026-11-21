package com.trustledger.core.shared.events;

import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes completed outbox rows older than {@code trustledger.events.retention} (7 days), daily
 * ({@code trustledger.events.cleanup-cron}). ShedLock (ADR 0013) keeps it to one instance at a time.
 * <p>
 * Only <b>completed</b> publications go: incomplete and failed ones are exactly what republishing needs, so they
 * stay however old they are. Correctness doesn't depend on this job; it keeps the table and its indexes small.
 */
@Component
public class EventPublicationCleanupJob {

    public static final String LOCK_NAME = "shared-event-publication-cleanup";

    private static final Logger log = LoggerFactory.getLogger(EventPublicationCleanupJob.class);

    private final CompletedEventPublications completed;
    private final LockingTaskExecutor locks;
    private final EventsProperties properties;
    private final Clock clock;

    public EventPublicationCleanupJob(CompletedEventPublications completed, LockingTaskExecutor locks,
            EventsProperties properties, Clock clock) {
        this.completed = completed;
        this.locks = locks;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${trustledger.events.cleanup-cron}")
    public void scheduled() {
        if (runLocked()) {
            log.info("Event publication cleanup deleted completed publications older than {}",
                    properties.retention());
        } else {
            log.info("Event publication cleanup skipped: another instance holds the lock");
        }
    }

    /** @return whether this instance ran the cleanup (false: another instance holds the lock) */
    public boolean runLocked() {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, properties.lockAtMostFor(),
                Duration.ZERO);
        TaskResult<Boolean> result;
        try {
            result = locks.executeWithLock(() -> {
                completed.deletePublicationsOlderThan(properties.retention());
                return Boolean.TRUE;
            }, lock);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Event publication cleanup failed", t);
        }
        return result.wasExecuted();
    }
}
