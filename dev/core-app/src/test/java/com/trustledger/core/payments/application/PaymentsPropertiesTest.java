package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.payments.application.PaymentsProperties.Idempotency;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class PaymentsPropertiesTest {

    private static Idempotency idempotency(Duration ttl, DataSize body, String cron, int batch, Duration lock) {
        return new Idempotency(ttl, body, cron, batch, lock);
    }

    private static final Duration DAY = Duration.ofHours(24);
    private static final DataSize KB16 = DataSize.ofKilobytes(16);
    private static final String CRON = "0 15 * * * *";
    private static final Duration TEN_MIN = Duration.ofMinutes(10);

    @Test
    void defaultsAreValid() {
        assertThat(idempotency(DAY, KB16, CRON, 1000, TEN_MIN).ttl()).isEqualTo(DAY);
    }

    @Test
    void ttlMustBeBetweenOneHourAndSevenDays() {
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(Duration.ofMinutes(59), KB16, CRON, 1, TEN_MIN))
                .withMessageContaining("ttl");
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(Duration.ofDays(8), KB16, CRON, 1, TEN_MIN))
                .withMessageContaining("ttl");
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(null, KB16, CRON, 1, TEN_MIN));
    }

    @Test
    void bodyLimitMustBeSensible() {
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(DAY, DataSize.ofBytes(100), CRON, 1, TEN_MIN))
                .withMessageContaining("max-body-size");
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(DAY, DataSize.ofMegabytes(2), CRON, 1, TEN_MIN))
                .withMessageContaining("max-body-size");
    }

    @Test
    void cronBatchAndLockAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(DAY, KB16, "every hour", 1, TEN_MIN))
                .withMessageContaining("cleanup-cron");
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(DAY, KB16, CRON, 0, TEN_MIN))
                .withMessageContaining("cleanup-batch-size");
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(DAY, KB16, CRON, 10_001, TEN_MIN))
                .withMessageContaining("cleanup-batch-size");
        assertThatIllegalArgumentException().isThrownBy(() -> idempotency(DAY, KB16, CRON, 1, Duration.ZERO))
                .withMessageContaining("lock-at-most-for");
    }

    @Test
    void zoneAndIdempotencyAreRequired() {
        Idempotency valid = idempotency(DAY, KB16, CRON, 1, TEN_MIN);
        assertThatNullPointerException().isThrownBy(() -> new PaymentsProperties(null, valid));
        assertThatNullPointerException().isThrownBy(() -> new PaymentsProperties(ZoneId.of("Asia/Kolkata"), null));
    }

    // --- binding, as at startup ---

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentsProperties.class)
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "trustledger.payments.limit-zone=Asia/Kolkata",
                    "trustledger.payments.idempotency.ttl=24h",
                    "trustledger.payments.idempotency.max-body-size=16KB",
                    "trustledger.payments.idempotency.cleanup-cron=0 15 * * * *",
                    "trustledger.payments.idempotency.cleanup-batch-size=1000",
                    "trustledger.payments.idempotency.lock-at-most-for=10m");

    @Test
    void bindsFromConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PaymentsProperties properties = context.getBean(PaymentsProperties.class);
            assertThat(properties.limitZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
            assertThat(properties.idempotency().ttl()).isEqualTo(DAY);
            assertThat(properties.idempotency().maxBodySize()).isEqualTo(KB16);
        });
    }

    @Test
    void invalidConfigurationFailsStartup() {
        runner.withPropertyValues("trustledger.payments.idempotency.ttl=10m")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("trustledger.payments.limit-zone=Mars/Olympus")
                .run(context -> assertThat(context).hasFailed());
    }
}
