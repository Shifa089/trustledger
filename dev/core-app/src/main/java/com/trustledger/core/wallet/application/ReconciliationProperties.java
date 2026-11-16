package com.trustledger.core.wallet.application;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@code trustledger.reconciliation.*}: the nightly wallet-vs-ledger check (FR-L-4). Startup fails on a value
 * that would silently disable or break it.
 *
 * @param cron                Spring cron (6 fields), evaluated in {@code zone}
 * @param batchSize           wallets per page; each page is one read-only snapshot and one ledger query
 * @param maxListedMismatches mismatches listed in the report; all of them are still counted and logged
 * @param lockAtMostFor       a crashed instance's lock expires after this; longer than any real run
 * @param lockAtLeastFor      the scheduled run keeps the lock this long, so instances with a slightly late
 *                            trigger don't run it a second time
 */
@ConfigurationProperties("trustledger.reconciliation")
public record ReconciliationProperties(String cron, ZoneId zone, int batchSize, int maxListedMismatches,
        Duration lockAtMostFor, Duration lockAtLeastFor) {

    public static final int MAX_BATCH_SIZE = 5_000;
    public static final int MAX_LISTED_MISMATCHES = 10_000;

    public ReconciliationProperties {
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("trustledger.reconciliation.cron must be a valid 6-field cron");
        }
        Objects.requireNonNull(zone, "trustledger.reconciliation.zone is required");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "trustledger.reconciliation.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (maxListedMismatches < 0 || maxListedMismatches > MAX_LISTED_MISMATCHES) {
            throw new IllegalArgumentException(
                    "trustledger.reconciliation.max-listed-mismatches must be between 0 and " + MAX_LISTED_MISMATCHES);
        }
        if (lockAtMostFor == null || lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            throw new IllegalArgumentException("trustledger.reconciliation.lock-at-most-for must be positive");
        }
        if (lockAtLeastFor == null || lockAtLeastFor.isNegative() || lockAtLeastFor.compareTo(lockAtMostFor) > 0) {
            throw new IllegalArgumentException(
                    "trustledger.reconciliation.lock-at-least-for must be between 0 and lock-at-most-for");
        }
    }
}
