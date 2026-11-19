package com.trustledger.core.payments.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.razorpay.*} (SPEC §6). Startup fails on anything unsafe: a live key, a missing secret, plain
 * HTTP to a remote host, or resilience settings that would never time out or never open the circuit.
 *
 * @param baseUrl        Razorpay API root ({@code https://api.razorpay.com}); tests point it at WireMock.
 *                       HTTPS is required except for localhost.
 * @param keyId          API key ID. Must be a TEST key ({@code rzp_test_...}); live keys are refused (CLAUDE.md).
 * @param keySecret      API key secret (Basic auth password). From the environment only.
 * @param webhookSecret  secret for {@code X-Razorpay-Signature} on webhooks. From the environment only.
 * @param connectTimeout TCP connect timeout, 100 ms to 10 s
 * @param timeout        per-attempt limit (TimeLimiter and socket read timeout), 100 ms to 10 s. SPEC: 3 s.
 * @param circuitBreaker circuit breaker around every call
 * @param retry          retries for idempotent GETs only
 */
@ConfigurationProperties("trustledger.razorpay")
public record RazorpayProperties(URI baseUrl, String keyId, String keySecret, String webhookSecret,
        Duration connectTimeout, Duration timeout, CircuitBreaker circuitBreaker, Retry retry) {

    static final String TEST_KEY_PREFIX = "rzp_test_";
    static final Duration MIN_TIMEOUT = Duration.ofMillis(100);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public RazorpayProperties {
        if (baseUrl == null || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("trustledger.razorpay.base-url must be an absolute URL");
        }
        String scheme = baseUrl.getScheme() == null ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
        boolean local = LOCAL_HOSTS.contains(baseUrl.getHost().toLowerCase(Locale.ROOT));
        if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
            throw new IllegalArgumentException(
                    "trustledger.razorpay.base-url must use https (plain http is allowed for localhost only)");
        }
        if (keyId == null || !keyId.startsWith(TEST_KEY_PREFIX) || keyId.length() == TEST_KEY_PREFIX.length()) {
            // Deliberately does not echo the value: it might be a live key.
            throw new IllegalArgumentException(
                    "trustledger.razorpay.key-id must be a Razorpay TEST key (rzp_test_...); live keys are refused");
        }
        requireSecret(keySecret, "key-secret");
        requireSecret(webhookSecret, "webhook-secret");
        requireBetween(connectTimeout, "connect-timeout");
        requireBetween(timeout, "timeout");
        if (circuitBreaker == null) {
            throw new IllegalArgumentException("trustledger.razorpay.circuit-breaker is required");
        }
        if (retry == null) {
            throw new IllegalArgumentException("trustledger.razorpay.retry is required");
        }
    }

    /** Never prints the secrets (records include every component in toString by default). */
    @Override
    public String toString() {
        return "RazorpayProperties[baseUrl=" + baseUrl + ", keyId=" + keyId + ", keySecret=***, webhookSecret=***"
                + ", connectTimeout=" + connectTimeout + ", timeout=" + timeout + ", circuitBreaker=" + circuitBreaker
                + ", retry=" + retry + "]";
    }

    private static void requireSecret(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trustledger.razorpay." + name + " is required");
        }
    }

    private static void requireBetween(Duration value, String name) {
        if (value == null || value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("trustledger.razorpay." + name + " must be between 100ms and 10s");
        }
    }

    /**
     * Count-based circuit breaker. SPEC M7: window 10, 50 %, 30 s open.
     *
     * @param slidingWindowSize         last N calls considered, 1 to 1000
     * @param failureRateThreshold      percent of failed calls that opens the circuit, 1 to 100
     * @param minimumNumberOfCalls      calls needed before the rate is evaluated, 1 to the window size
     * @param waitDurationInOpenState   how long the circuit stays open before a trial, 100 ms to 10 min
     * @param permittedCallsInHalfOpen  trial calls in half-open state, 1 to 100
     */
    public record CircuitBreaker(int slidingWindowSize, int failureRateThreshold, int minimumNumberOfCalls,
            Duration waitDurationInOpenState, int permittedCallsInHalfOpen) {

        public CircuitBreaker {
            if (slidingWindowSize < 1 || slidingWindowSize > 1000) {
                throw new IllegalArgumentException(
                        "trustledger.razorpay.circuit-breaker.sliding-window-size must be between 1 and 1000");
            }
            if (failureRateThreshold < 1 || failureRateThreshold > 100) {
                throw new IllegalArgumentException(
                        "trustledger.razorpay.circuit-breaker.failure-rate-threshold must be between 1 and 100");
            }
            if (minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize) {
                throw new IllegalArgumentException("trustledger.razorpay.circuit-breaker.minimum-number-of-calls "
                        + "must be between 1 and sliding-window-size");
            }
            if (waitDurationInOpenState == null || waitDurationInOpenState.compareTo(MIN_TIMEOUT) < 0
                    || waitDurationInOpenState.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("trustledger.razorpay.circuit-breaker.wait-duration-in-open-state "
                        + "must be between 100ms and 10m");
            }
            if (permittedCallsInHalfOpen < 1 || permittedCallsInHalfOpen > 100) {
                throw new IllegalArgumentException(
                        "trustledger.razorpay.circuit-breaker.permitted-calls-in-half-open must be between 1 and 100");
            }
        }
    }

    /**
     * Exponential backoff retry for GETs.
     *
     * @param maxAttempts    total attempts including the first, 1 (no retry) to 5
     * @param initialBackoff wait before the second attempt, 10 ms to 5 s
     * @param multiplier     backoff growth per attempt, 1.0 to 5.0 (a time factor, not money)
     */
    public record Retry(int maxAttempts, Duration initialBackoff, double multiplier) {

        public Retry {
            if (maxAttempts < 1 || maxAttempts > 5) {
                throw new IllegalArgumentException("trustledger.razorpay.retry.max-attempts must be between 1 and 5");
            }
            if (initialBackoff == null || initialBackoff.compareTo(Duration.ofMillis(10)) < 0
                    || initialBackoff.compareTo(Duration.ofSeconds(5)) > 0) {
                throw new IllegalArgumentException(
                        "trustledger.razorpay.retry.initial-backoff must be between 10ms and 5s");
            }
            if (!(multiplier >= 1.0 && multiplier <= 5.0)) {
                throw new IllegalArgumentException("trustledger.razorpay.retry.multiplier must be between 1.0 and 5.0");
            }
        }
    }
}
