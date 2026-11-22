package com.trustledger.core.ledger.application;

import com.trustledger.core.shared.cache.CacheNames;
import com.trustledger.core.shared.cache.CacheProperties;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/** The {@link CacheNames#LEDGER_ACCOUNTS} cache: {@link LedgerAccountRef} as JSON, TTL from configuration. */
@Configuration(proxyBeanMethods = false)
class LedgerCacheConfig {

    @Bean
    RedisCacheManagerBuilderCustomizer ledgerAccountsCache(CacheProperties properties) {
        return builder -> builder.withCacheConfiguration(CacheNames.LEDGER_ACCOUNTS, builder.cacheDefaults()
                .entryTtl(properties.ttlOf(CacheNames.LEDGER_ACCOUNTS))
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(LedgerAccountRef.class))));
    }
}
