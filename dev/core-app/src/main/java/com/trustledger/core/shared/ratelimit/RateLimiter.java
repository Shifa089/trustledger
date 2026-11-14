package com.trustledger.core.shared.ratelimit;

import java.time.Duration;

/** Takes one token from a named per-minute bucket. Implemented with Bucket4j on Redis. */
public interface RateLimiter {

    /**
     * @param key       bucket key, e.g. {@code rl:money:<subject>}
     * @param perMinute bucket capacity, refilled evenly over one minute
     */
    Decision tryConsume(String key, int perMinute);

    /** @param retryAfter how long until a token is available again (zero when allowed) */
    record Decision(boolean allowed, long remaining, Duration retryAfter) {

        public static Decision allow(long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }

        public static Decision reject(Duration retryAfter) {
            return new Decision(false, 0, retryAfter);
        }
    }
}
