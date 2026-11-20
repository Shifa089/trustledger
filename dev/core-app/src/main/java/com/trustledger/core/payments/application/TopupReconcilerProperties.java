package com.trustledger.core.payments.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@code trustledger.payments.reconciler.*}: the {@link PendingTopupReconciler} (FR-P-6). Startup fails on a value
 * that would make it check too early, never expire, or run unbounded.
 *
 * @param cron          Spring cron (6 fields); every 5 minutes in {@code application.yml}
 * @param pendingAfter  PENDING top-ups older than this are checked at the gateway (SPEC: 15 minutes); at least 1m
 * @param expireAfter   PENDING top-ups older than this with nothing captured become EXPIRED (24h); longer than
 *                      {@code pendingAfter}
 * @param batchSize     top-ups checked per run, oldest first; between 1 and 1,000
 * @param lockAtMostFor a crashed instance's ShedLock lock expires after this
 */
@ConfigurationProperties("trustledger.payments.reconciler")
public record TopupReconcilerProperties(String cron, Duration pendingAfter, Duration expireAfter, int batchSize,
        Duration lockAtMostFor) {

    public static final Duration MIN_PENDING_AFTER = Duration.ofMinutes(1);
    public static final int MAX_BATCH_SIZE = 1_000;

    public TopupReconcilerProperties {
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("trustledger.payments.reconciler.cron must be a valid 6-field cron");
        }
        if (pendingAfter == null || pendingAfter.compareTo(MIN_PENDING_AFTER) < 0) {
            throw new IllegalArgumentException("trustledger.payments.reconciler.pending-after must be at least 1m");
        }
        if (expireAfter == null || expireAfter.compareTo(pendingAfter) <= 0) {
            throw new IllegalArgumentException(
                    "trustledger.payments.reconciler.expire-after must be longer than pending-after");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "trustledger.payments.reconciler.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (lockAtMostFor == null || lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            throw new IllegalArgumentException("trustledger.payments.reconciler.lock-at-most-for must be positive");
        }
    }
}
