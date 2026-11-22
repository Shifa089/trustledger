package com.trustledger.core.privacy.infrastructure;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrivacyStorageProperties.class)
class PrivacyStorageConfig {

    @Bean
    S3ExportStorage exportStorage(PrivacyStorageProperties properties) {
        return new S3ExportStorage(properties);
    }

    /** Local profile and tests only ({@code create-bucket: true}). */
    @Bean
    @ConditionalOnProperty(name = "trustledger.privacy.storage.create-bucket", havingValue = "true")
    ApplicationRunner exportBucketInitializer(S3ExportStorage storage) {
        return args -> storage.createBucketIfMissing();
    }
}
