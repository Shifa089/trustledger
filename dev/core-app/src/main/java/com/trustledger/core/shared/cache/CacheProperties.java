package com.trustledger.core.shared.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.cache.*}: the time-to-live of every Redis cache (ADR 0019). Only the caches listed in
 * {@link CacheNames} exist; each must have a positive TTL here, so an entry can never live forever. Validated in
 * the constructor, so a missing or bad value stops startup.
 *
 * @param ttl cache name -> time to live
 */
@ConfigurationProperties("trustledger.cache")
public record CacheProperties(Map<String, Duration> ttl) {

    public CacheProperties {
        ttl = ttl == null ? Map.of() : Map.copyOf(ttl);
        for (String name : CacheNames.ALL) {
            Duration value = ttl.get(name);
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("trustledger.cache.ttl." + name + " is required and must be > 0");
            }
        }
        for (String name : ttl.keySet()) {
            if (!CacheNames.ALL.contains(name)) {
                throw new IllegalArgumentException("trustledger.cache.ttl." + name + " is not a known cache");
            }
        }
    }

    /** The TTL of a known cache. */
    public Duration ttlOf(String cacheName) {
        return Objects.requireNonNull(ttl.get(cacheName), () -> "Unknown cache " + cacheName);
    }
}
