package com.trustledger.core.audit.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.audit.*}. Validated in the constructor, so a bad value stops startup.
 *
 * @param verifyBatchSize rows read per query while {@code GET /audit/verify} walks the chain; between 1 and
 *                        {@link #MAX_VERIFY_BATCH_SIZE}
 */
@ConfigurationProperties("trustledger.audit")
public record AuditProperties(int verifyBatchSize) {

    /** One batch is held in memory at a time. */
    public static final int MAX_VERIFY_BATCH_SIZE = 10_000;

    public AuditProperties {
        if (verifyBatchSize < 1 || verifyBatchSize > MAX_VERIFY_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "trustledger.audit.verify-batch-size must be between 1 and " + MAX_VERIFY_BATCH_SIZE);
        }
    }
}
