package com.trustledger.core.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RazorpayPropertiesTest {

    static final URI BASE = URI.create("https://api.razorpay.com");
    static final String KEY = "rzp_test_FAKEKEY0000000";
    static final Duration SECOND = Duration.ofSeconds(1);
    static final Duration THREE = Duration.ofSeconds(3);
    static final RazorpayProperties.CircuitBreaker CB =
            new RazorpayProperties.CircuitBreaker(10, 50, 10, Duration.ofSeconds(30), 3);
    static final RazorpayProperties.Retry RETRY = new RazorpayProperties.Retry(3, Duration.ofMillis(200), 2.0);

    static RazorpayProperties props(URI base, String keyId, String secret, String webhookSecret, Duration timeout) {
        return new RazorpayProperties(base, keyId, secret, webhookSecret, SECOND, timeout, CB, RETRY);
    }

    @Test
    void specValuesAreValid() {
        RazorpayProperties p = props(BASE, KEY, "s", "w", THREE);
        assertThat(p.timeout()).isEqualTo(THREE);
        assertThat(p.circuitBreaker()).isEqualTo(CB);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rzp_live_ABCDEF123456", "rzp_test_", "ABCDEF", ""})
    void onlyTestKeysAreAccepted(String keyId) {
        assertThatIllegalArgumentException().isThrownBy(() -> props(BASE, keyId, "s", "w", THREE))
                .withMessageContaining("TEST key")
                .withMessageNotContaining("rzp_live_ABCDEF123456");
    }

    @Test
    void secretsAreRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> props(BASE, KEY, " ", "w", THREE))
                .withMessageContaining("key-secret");
        assertThatIllegalArgumentException().isThrownBy(() -> props(BASE, KEY, "s", null, THREE))
                .withMessageContaining("webhook-secret");
    }

    @Test
    void plainHttpOnlyForLocalhost() {
        assertThat(props(URI.create("http://localhost:8089"), KEY, "s", "w", THREE)).isNotNull();
        assertThat(props(URI.create("http://127.0.0.1:8089"), KEY, "s", "w", THREE)).isNotNull();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> props(URI.create("http://api.razorpay.com"), KEY, "s", "w", THREE))
                .withMessageContaining("https");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> props(URI.create("ftp://localhost"), KEY, "s", "w", THREE));
        assertThatIllegalArgumentException().isThrownBy(() -> props(URI.create("/v1"), KEY, "s", "w", THREE))
                .withMessageContaining("absolute");
    }

    @Test
    void timeoutsAreBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> props(BASE, KEY, "s", "w", Duration.ofMillis(99)))
                .withMessageContaining("timeout");
        assertThatIllegalArgumentException().isThrownBy(() -> props(BASE, KEY, "s", "w", Duration.ofSeconds(11)))
                .withMessageContaining("timeout");
        assertThatIllegalArgumentException().isThrownBy(() -> props(BASE, KEY, "s", "w", null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RazorpayProperties(BASE, KEY, "s", "w", Duration.ZERO, THREE, CB, RETRY))
                .withMessageContaining("connect-timeout");
    }

    @Test
    void circuitBreakerSettingsAreBounded() {
        Duration open = Duration.ofSeconds(30);
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.CircuitBreaker(0, 50, 1, open, 3))
                .withMessageContaining("sliding-window-size");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.CircuitBreaker(10, 0, 10, open, 3))
                .withMessageContaining("failure-rate-threshold");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.CircuitBreaker(10, 101, 10, open, 3))
                .withMessageContaining("failure-rate-threshold");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.CircuitBreaker(10, 50, 11, open, 3))
                .withMessageContaining("minimum-number-of-calls");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RazorpayProperties.CircuitBreaker(10, 50, 10, Duration.ZERO, 3))
                .withMessageContaining("wait-duration-in-open-state");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.CircuitBreaker(10, 50, 10, open, 0))
                .withMessageContaining("permitted-calls-in-half-open");
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RazorpayProperties(BASE, KEY, "s", "w", SECOND, THREE, null, RETRY))
                .withMessageContaining("circuit-breaker");
    }

    @Test
    void retrySettingsAreBounded() {
        Duration backoff = Duration.ofMillis(200);
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.Retry(0, backoff, 2.0))
                .withMessageContaining("max-attempts");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.Retry(6, backoff, 2.0))
                .withMessageContaining("max-attempts");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.Retry(3, Duration.ZERO, 2.0))
                .withMessageContaining("initial-backoff");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.Retry(3, backoff, 0.5))
                .withMessageContaining("multiplier");
        assertThatIllegalArgumentException().isThrownBy(() -> new RazorpayProperties.Retry(3, backoff, Double.NaN))
                .withMessageContaining("multiplier");
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RazorpayProperties(BASE, KEY, "s", "w", SECOND, THREE, CB, null))
                .withMessageContaining("retry");
    }

    @Test
    void toStringHidesTheSecrets() {
        String text = props(BASE, KEY, "key-secret-value", "webhook-secret-value", THREE).toString();

        assertThat(text).contains(KEY).doesNotContain("key-secret-value").doesNotContain("webhook-secret-value");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RazorpayProperties.class)
    static class Binding {
    }

    @Test
    void bindsFromKebabCaseProperties() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues(
                        "trustledger.razorpay.base-url=https://api.razorpay.com",
                        "trustledger.razorpay.key-id=" + KEY,
                        "trustledger.razorpay.key-secret=s",
                        "trustledger.razorpay.webhook-secret=w",
                        "trustledger.razorpay.connect-timeout=1s",
                        "trustledger.razorpay.timeout=3s",
                        "trustledger.razorpay.circuit-breaker.sliding-window-size=10",
                        "trustledger.razorpay.circuit-breaker.failure-rate-threshold=50",
                        "trustledger.razorpay.circuit-breaker.minimum-number-of-calls=10",
                        "trustledger.razorpay.circuit-breaker.wait-duration-in-open-state=30s",
                        "trustledger.razorpay.circuit-breaker.permitted-calls-in-half-open=3",
                        "trustledger.razorpay.retry.max-attempts=3",
                        "trustledger.razorpay.retry.initial-backoff=200ms",
                        "trustledger.razorpay.retry.multiplier=2.0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RazorpayProperties p = context.getBean(RazorpayProperties.class);
                    assertThat(p.timeout()).isEqualTo(THREE);
                    assertThat(p.circuitBreaker()).isEqualTo(CB);
                    assertThat(p.retry()).isEqualTo(RETRY);
                });
    }

    @Test
    void liveKeyFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues(
                        "trustledger.razorpay.base-url=https://api.razorpay.com",
                        "trustledger.razorpay.key-id=rzp_live_ABCDEF123456",
                        "trustledger.razorpay.key-secret=s",
                        "trustledger.razorpay.webhook-secret=w",
                        "trustledger.razorpay.connect-timeout=1s",
                        "trustledger.razorpay.timeout=3s",
                        "trustledger.razorpay.circuit-breaker.sliding-window-size=10",
                        "trustledger.razorpay.circuit-breaker.failure-rate-threshold=50",
                        "trustledger.razorpay.circuit-breaker.minimum-number-of-calls=10",
                        "trustledger.razorpay.circuit-breaker.wait-duration-in-open-state=30s",
                        "trustledger.razorpay.circuit-breaker.permitted-calls-in-half-open=3",
                        "trustledger.razorpay.retry.max-attempts=3",
                        "trustledger.razorpay.retry.initial-backoff=200ms",
                        "trustledger.razorpay.retry.multiplier=2.0")
                .run(context -> assertThat(context).hasFailed());
    }
}
