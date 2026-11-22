package com.trustledger.core.shared.cache;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Spring's cache abstraction on Redis (M11, ADR 0019), for rarely changing reference data only. See
 * {@link CacheNames} for what is cached and why <b>balances never are</b>.
 * <ul>
 *   <li>Defaults: string keys under {@code tl:cache:<cache>::}, no {@code null} values (a missing ledger account
 *       is not remembered, so an account opened a moment later is found).</li>
 *   <li>Each module adds its cache with a typed JSON serializer and its TTL from {@link CacheProperties}
 *       (a {@code RedisCacheManagerBuilderCustomizer} in the module); creating any other cache is refused.</li>
 *   <li>Fail open: a Redis error is logged and the method runs as if there were no cache, so a Redis outage slows
 *       reads down instead of failing them.</li>
 * </ul>
 * The caches are used only on paths that hold no row locks (CLAUDE.md: no remote calls while holding locks); money
 * movement reads limits and accounts from PostgreSQL inside its own transaction.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
class CacheConfig implements CachingConfigurer {

    static final String KEY_PREFIX = "tl:cache:";

    /** Picked up by Boot's Redis cache auto-configuration as the defaults every cache starts from. */
    @Bean
    RedisCacheConfiguration redisCacheDefaults() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(KEY_PREFIX)
                .disableCachingNullValues()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()));
    }

    /**
     * Only the caches in {@link CacheNames} exist, and writes are <b>immediate</b>. Spring Data Redis 4 otherwise
     * writes a cache entry asynchronously on Lettuce (fire and forget): the call returns before Redis has the value,
     * and a failed write never reaches the {@link #errorHandler()}. Found by {@code RedisCacheIT} (Day 22). The cost
     * is one Redis round trip on a cache miss, on lock-free read paths only.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer onlyKnownCachesWithImmediateWrites(RedisConnectionFactory connections) {
        return builder -> builder.disableCreateOnMissingCache()
                .cacheWriter(RedisCacheWriter.create(connections, writer -> writer.immediateWrites()));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(CacheConfig.class.getName(), false);
    }
}
