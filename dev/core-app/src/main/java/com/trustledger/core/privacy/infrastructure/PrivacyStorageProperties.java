package com.trustledger.core.privacy.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.privacy.storage.*}: where data exports are stored (ADR 0011). A bucket of its own, separate
 * from KYC documents: exports are short-lived copies (an S3 lifecycle rule expires them, M14) and must never share
 * a retention policy with regulatory records.
 *
 * @param bucket       S3 bucket name; required
 * @param region       AWS region, e.g. {@code ap-south-1}; required
 * @param endpoint     endpoint override (LocalStack locally and in tests); empty means real AWS
 * @param pathStyle    path-style URLs ({@code host/bucket/key}); needed by LocalStack
 * @param accessKey    static credentials, for LocalStack only; empty means the default AWS chain
 * @param secretKey    see {@code accessKey}; both or neither
 * @param createBucket create the bucket at startup if missing; local profile and tests only
 */
@ConfigurationProperties("trustledger.privacy.storage")
public record PrivacyStorageProperties(String bucket, String region, URI endpoint, boolean pathStyle,
        String accessKey, String secretKey, boolean createBucket) {

    public PrivacyStorageProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("trustledger.privacy.storage.bucket is required");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("trustledger.privacy.storage.region is required");
        }
        if (isBlank(accessKey) != isBlank(secretKey)) {
            throw new IllegalArgumentException(
                    "trustledger.privacy.storage.access-key and secret-key must be set together");
        }
    }

    boolean hasStaticCredentials() {
        return !isBlank(accessKey);
    }

    /** Never print the secret key. */
    @Override
    public String toString() {
        return "PrivacyStorageProperties[bucket=" + bucket + ", region=" + region + ", endpoint=" + endpoint
                + ", pathStyle=" + pathStyle + ", staticCredentials=" + hasStaticCredentials() + ", createBucket="
                + createBucket + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
