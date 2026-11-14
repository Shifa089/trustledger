package com.trustledger.core.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * {@code trustledger.rate-limit.*} (SPEC M2): per-user request budgets. Validated in the constructor,
 * so a typo stops startup instead of silently disabling a limit.
 *
 * @param enabled          master switch (on by default)
 * @param defaultPerMinute budget for every authenticated {@code /api/**} request not listed below
 * @param moneyPerMinute   budget for money-moving endpoints (separate bucket)
 * @param moneyEndpoints   {@code "METHOD /path/pattern"} entries, e.g. {@code "POST /api/*\/transfers"}
 * @param redisTimeout     maximum wait for Redis per request; on timeout or error the request is let
 *                         through (fail open) and a warning is logged
 */
@ConfigurationProperties("trustledger.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int defaultPerMinute,
        @DefaultValue("20") int moneyPerMinute,
        @DefaultValue({"POST /api/*/transfers", "POST /api/*/topups"}) List<String> moneyEndpoints,
        @DefaultValue("500ms") Duration redisTimeout) {

    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    public RateLimitProperties {
        requirePositive("default-per-minute", defaultPerMinute);
        requirePositive("money-per-minute", moneyPerMinute);
        moneyEndpoints = moneyEndpoints == null ? List.of() : List.copyOf(moneyEndpoints);
        moneyEndpoints.forEach(RateLimitProperties::parse);
        if (redisTimeout == null || redisTimeout.isNegative() || redisTimeout.isZero()) {
            throw new IllegalArgumentException("trustledger.rate-limit.redis-timeout must be positive");
        }
    }

    /** One money endpoint: an HTTP method and a path pattern. */
    record Endpoint(HttpMethod method, PathPattern pattern) {
    }

    List<Endpoint> parsedMoneyEndpoints() {
        return moneyEndpoints.stream().map(RateLimitProperties::parse).toList();
    }

    static Endpoint parse(String entry) {
        String[] parts = entry == null ? new String[0] : entry.strip().split("\\s+");
        if (parts.length != 2 || !METHODS.contains(parts[0].toUpperCase(Locale.ROOT)) || !parts[1].startsWith("/")) {
            throw new IllegalArgumentException(
                    "trustledger.rate-limit.money-endpoints entries must look like 'POST /api/*/transfers': " + entry);
        }
        return new Endpoint(HttpMethod.valueOf(parts[0].toUpperCase(Locale.ROOT)),
                PathPatternParser.defaultInstance.parse(parts[1]));
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("trustledger.rate-limit." + name + " must be at least 1");
        }
    }
}
