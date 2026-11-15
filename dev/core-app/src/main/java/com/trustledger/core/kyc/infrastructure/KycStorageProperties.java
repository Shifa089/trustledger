package com.trustledger.core.kyc.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.kyc.storage.*}: where KYC documents are stored (ADR 0011).
 *
 * @param bucket       S3 bucket name; required
 * @param region       AWS region, e.g. {@code ap-south-1}; required
 * @param endpoint     endpoint override (LocalStack locally and in tests); empty means real AWS
 * @param pathStyle    path-style URLs ({@code host/bucket/key}); needed by LocalStack
 * @param accessKey    static credentials, for LocalStack only; empty means the default AWS chain
 *                     (environment, profile, instance or task role)
 * @param secretKey    see {@code accessKey}; both or neither
 * @param createBucket create the bucket at startup if missing. Local profile and tests only: in AWS the
 *                     bucket, its encryption and its public-access block are infrastructure (Terraform, M14)
 */
@ConfigurationProperties("trustledger.kyc.storage")
public record KycStorageProperties(String bucket, String region, URI endpoint, boolean pathStyle,
        String accessKey, String secretKey, boolean createBucket) {

    public KycStorageProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("trustledger.kyc.storage.bucket is required");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("trustledger.kyc.storage.region is required");
        }
        if (isBlank(accessKey) != isBlank(secretKey)) {
            throw new IllegalArgumentException(
                    "trustledger.kyc.storage.access-key and secret-key must be set together");
        }
    }

    boolean hasStaticCredentials() {
        return !isBlank(accessKey);
    }

    /** Never print the secret key. */
    @Override
    public String toString() {
        return "KycStorageProperties[bucket=" + bucket + ", region=" + region + ", endpoint=" + endpoint
                + ", pathStyle=" + pathStyle + ", staticCredentials=" + hasStaticCredentials() + ", createBucket="
                + createBucket + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
