package com.trustledger.core.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

class RateLimitPropertiesTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    @Test
    void parsesMoneyEndpoints() {
        RateLimitProperties properties = new RateLimitProperties(true, 100, 20,
                List.of("POST /api/*/transfers", "post   /api/*/topups"), TIMEOUT);

        assertThat(properties.parsedMoneyEndpoints())
                .extracting(RateLimitProperties.Endpoint::method)
                .containsExactly(HttpMethod.POST, HttpMethod.POST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/api/*/transfers", "FETCH /api/*/transfers", "POST api/transfers",
            "POST /a /b"})
    void malformedEndpointFailsStartup(String entry) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 100, 20, List.of(entry), TIMEOUT))
                .withMessageContaining("money-endpoints");
    }

    @Test
    void limitsMustBePositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 0, 20, List.of(), TIMEOUT))
                .withMessageContaining("default-per-minute");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 100, -1, List.of(), TIMEOUT))
                .withMessageContaining("money-per-minute");
    }

    @Test
    void redisTimeoutMustBePositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 100, 20, List.of(), Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimitProperties(true, 100, 20, List.of(), null));
    }

    @Test
    void nullEndpointListMeansNoMoneyEndpoints() {
        assertThat(new RateLimitProperties(true, 100, 20, null, TIMEOUT).moneyEndpoints()).isEmpty();
    }
}
