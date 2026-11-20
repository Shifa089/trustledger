package com.trustledger.core.payments.application;

import com.trustledger.core.shared.Money;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.unit.DataSize;

/**
 * {@code trustledger.payments.*}. Startup fails on a value that would break idempotency, the limit windows or
 * top-ups.
 *
 * @param limitZone   where a "day" and a "month" start for the daily and monthly transfer limits and the monthly
 *                    load limit ({@code Asia/Kolkata}: an Indian customer's day, not UTC's)
 * @param idempotency the {@code Idempotency-Key} settings
 * @param topups      the top-up settings (M7)
 * @param webhooks    the gateway webhook settings (M7)
 */
@ConfigurationProperties("trustledger.payments")
public record PaymentsProperties(ZoneId limitZone, Idempotency idempotency, Topups topups, Webhooks webhooks) {

    public PaymentsProperties {
        Objects.requireNonNull(limitZone, "trustledger.payments.limit-zone is required");
        Objects.requireNonNull(idempotency, "trustledger.payments.idempotency is required");
        Objects.requireNonNull(topups, "trustledger.payments.topups is required");
        Objects.requireNonNull(webhooks, "trustledger.payments.webhooks is required");
    }

    /**
     * @param ttl              how long a key is remembered (SPEC: 24 hours); between 1 hour and 7 days
     * @param maxBodySize      largest request body cached for hashing; between 1 KB and 1 MB
     * @param cleanupCron      Spring cron (6 fields) of the job that deletes expired keys
     * @param cleanupBatchSize rows deleted per transaction; between 1 and 10,000
     * @param lockAtMostFor    a crashed instance's cleanup lock expires after this
     */
    public record Idempotency(Duration ttl, DataSize maxBodySize, String cleanupCron, int cleanupBatchSize,
            Duration lockAtMostFor) {

        public static final Duration MIN_TTL = Duration.ofHours(1);
        public static final Duration MAX_TTL = Duration.ofDays(7);
        public static final DataSize MIN_BODY = DataSize.ofKilobytes(1);
        public static final DataSize MAX_BODY = DataSize.ofMegabytes(1);
        public static final int MAX_BATCH_SIZE = 10_000;

        public Idempotency {
            if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
                throw new IllegalArgumentException("trustledger.payments.idempotency.ttl must be between 1h and 7d");
            }
            if (maxBodySize == null || maxBodySize.compareTo(MIN_BODY) < 0 || maxBodySize.compareTo(MAX_BODY) > 0) {
                throw new IllegalArgumentException(
                        "trustledger.payments.idempotency.max-body-size must be between 1KB and 1MB");
            }
            if (cleanupCron == null || !CronExpression.isValidExpression(cleanupCron)) {
                throw new IllegalArgumentException(
                        "trustledger.payments.idempotency.cleanup-cron must be a valid 6-field cron");
            }
            if (cleanupBatchSize < 1 || cleanupBatchSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        "trustledger.payments.idempotency.cleanup-batch-size must be between 1 and " + MAX_BATCH_SIZE);
            }
            if (lockAtMostFor == null || lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
                throw new IllegalArgumentException(
                        "trustledger.payments.idempotency.lock-at-most-for must be positive");
            }
        }
    }

    /**
     * @param minAmount    smallest top-up accepted, a positive INR amount (Razorpay's own minimum is ₹1.00, so a
     *                     smaller order would come back as a confusing 502 instead of a clear 400)
     * @param checkoutPage serve the manual-test {@code /checkout.html} page; only the {@code local} profile turns it on
     */
    public record Topups(Money minAmount, boolean checkoutPage) {

        public Topups {
            if (minAmount == null || !Money.INR.equals(minAmount.currency()) || minAmount.isZero()) {
                throw new IllegalArgumentException(
                        "trustledger.payments.topups.min-amount must be a positive INR amount");
            }
        }
    }

    /**
     * @param maxBodySize largest webhook body accepted (larger: 413, not stored); between 1 KB and 1 MB. The
     *                    endpoint is public and stores every delivery, so this bounds what one request can write.
     */
    public record Webhooks(DataSize maxBodySize) {

        public static final DataSize MIN_BODY = DataSize.ofKilobytes(1);
        public static final DataSize MAX_BODY = DataSize.ofMegabytes(1);

        public Webhooks {
            if (maxBodySize == null || maxBodySize.compareTo(MIN_BODY) < 0 || maxBodySize.compareTo(MAX_BODY) > 0) {
                throw new IllegalArgumentException(
                        "trustledger.payments.webhooks.max-body-size must be between 1KB and 1MB");
            }
        }
    }
}
