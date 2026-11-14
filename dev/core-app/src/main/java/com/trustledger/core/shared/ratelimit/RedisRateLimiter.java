package com.trustledger.core.shared.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.DisposableBean;

/**
 * Bucket4j buckets stored in Redis, so every core-app instance shares the same per-user budget.
 * Bucket4j updates a bucket with compare-and-swap, so concurrent requests cannot both take the last token.
 * <p>
 * The Redis connection is opened lazily on first use (and retried after a failure), so the application
 * starts even when Redis is down; {@link RateLimitFilter} then fails open. Bucket keys expire once the
 * bucket would be full again, so idle users leave nothing behind in Redis.
 */
class RedisRateLimiter implements RateLimiter, DisposableBean {

    /**
     * Interval refill: the whole budget comes back one minute after the bucket started, i.e. "N requests
     * per minute" exactly as the spec states it, and the (N+1)-th request within the minute is rejected.
     * (A greedy refill would hand back a token every 60/N seconds, so a burst of N+1 requests spread over a
     * few seconds could pass. The trade-off of intervals: up to 2N requests around a window boundary.)
     */
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final Duration KEY_GRACE = Duration.ofSeconds(10);

    private final RedisClient client;
    private final Duration timeout;
    private final ReentrantLock connectLock = new ReentrantLock();
    private volatile StatefulRedisConnection<String, byte[]> connection;
    private volatile ProxyManager<String> buckets;

    RedisRateLimiter(RedisClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public Decision tryConsume(String key, int perMinute) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillIntervally(perMinute, REFILL_PERIOD))
                .build();
        ConsumptionProbe probe = buckets().getProxy(key, () -> configuration).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
                ? Decision.allow(probe.getRemainingTokens())
                : Decision.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private ProxyManager<String> buckets() {
        ProxyManager<String> current = buckets;
        if (current != null) {
            return current;
        }
        connectLock.lock();
        try {
            if (buckets == null) {
                StatefulRedisConnection<String, byte[]> conn =
                        client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                conn.setTimeout(timeout);
                connection = conn;
                buckets = Bucket4jLettuce.casBasedBuilder(conn)
                        .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEY_GRACE))
                        .requestTimeout(timeout)
                        .build();
            }
            return buckets;
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void destroy() {
        StatefulRedisConnection<String, byte[]> conn = connection;
        if (conn != null) {
            conn.close();
        }
        client.shutdown();
    }
}
