package com.trustledger.core.kyc.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The S3 client and presigner for KYC documents (plain AWS SDK v2, ADR 0011). Nothing connects at startup:
 * credentials and the endpoint are used on the first request, so the app starts even if S3 is down.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KycStorageProperties.class)
class S3StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(S3StorageConfig.class);

    @Bean(destroyMethod = "close")
    S3Client kycS3Client(KycStorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(s3Configuration(properties));
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner kycS3Presigner(KycStorageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(s3Configuration(properties));
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    /** Local profile and tests only ({@code create-bucket: true}); in AWS the bucket is provisioned by Terraform. */
    @Bean
    @ConditionalOnProperty(name = "trustledger.kyc.storage.create-bucket", havingValue = "true")
    ApplicationRunner kycBucketInitializer(S3Client s3, KycStorageProperties properties) {
        return args -> {
            try {
                s3.createBucket(b -> b.bucket(properties.bucket()));
                log.info("Created KYC document bucket {}", properties.bucket());
            } catch (BucketAlreadyOwnedByYouException exists) {
                log.debug("KYC document bucket {} already exists", properties.bucket());
            }
        };
    }

    private static S3Configuration s3Configuration(KycStorageProperties properties) {
        return S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle()).build();
    }

    private static AwsCredentialsProvider credentials(KycStorageProperties properties) {
        if (properties.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
