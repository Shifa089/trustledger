package com.trustledger.core.shared.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires Bucket4j to Redis. Uses Boot's {@link DataRedisConnectionDetails}, so host, port and password
 * come from {@code spring.data.redis.*} locally and from the Testcontainers service connection in tests.
 * Bucket4j needs a raw Lettuce client (not Spring's {@code RedisTemplate}), so it gets its own, with
 * short timeouts: a slow Redis must not make every API request slow.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

    @Bean
    RedisRateLimiter rateLimiter(DataRedisConnectionDetails redis, RateLimitProperties properties) {
        DataRedisConnectionDetails.Standalone standalone = redis.getStandalone();
        if (standalone == null) {
            throw new IllegalStateException("Rate limiting supports a standalone Redis only (sentinel/cluster: M14)");
        }
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withTimeout(properties.redisTimeout());
        if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
            if (redis.getUsername() != null && !redis.getUsername().isEmpty()) {
                uri.withAuthentication(redis.getUsername(), redis.getPassword().toCharArray());
            } else {
                uri.withPassword(redis.getPassword().toCharArray());
            }
        }
        if (redis.getSslBundle() != null) {
            uri.withSsl(true);
        }
        RedisClient client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.redisTimeout()).build())
                // While disconnected, fail commands at once instead of queueing them until Redis returns.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        return new RedisRateLimiter(client, properties.redisTimeout());
    }

    @Bean
    RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties, JsonMapper jsonMapper) {
        return new RateLimitFilter(rateLimiter, properties, jsonMapper);
    }
}
