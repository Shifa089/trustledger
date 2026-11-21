package com.trustledger.core.shared.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/**
 * {@code trustledger.events.*}: the outbox and its Kafka topics (M8, ADR 0005). Startup fails on unsafe values.
 *
 * @param retention     completed publications older than this are deleted (7 days); at least 1 day, so a recent
 *                      send can still be looked up when a consumer reports a problem
 * @param cleanupCron   Spring cron (6 fields) for {@link EventPublicationCleanupJob}
 * @param lockAtMostFor a crashed instance's ShedLock lock expires after this
 * @param topics        topic creation; only local and test turn it on (in AWS topics come from infrastructure)
 */
@ConfigurationProperties("trustledger.events")
public record EventsProperties(Duration retention, String cleanupCron, Duration lockAtMostFor, Topics topics) {

    public static final Duration MIN_RETENTION = Duration.ofDays(1);

    public EventsProperties {
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalArgumentException("trustledger.events.retention must be at least 1d");
        }
        if (cleanupCron == null || !CronExpression.isValidExpression(cleanupCron)) {
            throw new IllegalArgumentException("trustledger.events.cleanup-cron must be a valid 6-field cron");
        }
        if (lockAtMostFor == null || lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            throw new IllegalArgumentException("trustledger.events.lock-at-most-for must be positive");
        }
        if (topics == null) {
            topics = new Topics(false, 3, (short) 1);
        }
    }

    /**
     * @param create     create the topics at startup ({@code NewTopic} beans)
     * @param partitions partitions per topic (3); ordering holds per wallet, since the wallet ID is the key
     * @param replicas   replication factor (1 locally: a single broker)
     */
    public record Topics(boolean create, int partitions, short replicas) {

        public Topics {
            if (partitions < 1) {
                throw new IllegalArgumentException("trustledger.events.topics.partitions must be at least 1");
            }
            if (replicas < 1) {
                throw new IllegalArgumentException("trustledger.events.topics.replicas must be at least 1");
            }
        }
    }
}
