package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.cache.CacheNames;
import com.trustledger.core.shared.cache.CacheProperties;
import com.trustledger.core.wallet.WalletLimits;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/** The {@link CacheNames#WALLET_LIMITS} cache: {@link WalletLimits} as JSON, TTL from configuration. */
@Configuration(proxyBeanMethods = false)
class WalletCacheConfig {

    @Bean
    RedisCacheManagerBuilderCustomizer walletLimitsCache(CacheProperties properties) {
        return builder -> builder.withCacheConfiguration(CacheNames.WALLET_LIMITS, builder.cacheDefaults()
                .entryTtl(properties.ttlOf(CacheNames.WALLET_LIMITS))
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(WalletLimits.class))));
    }
}
