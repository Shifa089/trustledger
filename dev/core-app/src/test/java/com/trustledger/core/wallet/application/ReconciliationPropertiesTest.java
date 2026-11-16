package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ReconciliationPropertiesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static ReconciliationProperties props(String cron, int batch, int listed, Duration most, Duration least) {
        return new ReconciliationProperties(cron, IST, batch, listed, most, least);
    }

    private static ReconciliationProperties valid() {
        return props("0 0 2 * * *", 500, 1000, Duration.ofMinutes(30), Duration.ofMinutes(1));
    }

    @Test
    void defaultsAreValid() {
        assertThat(valid().batchSize()).isEqualTo(500);
    }

    @Test
    void cronMustBeAValidSixFieldExpression() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("0 2 * * *", 500, 10, Duration.ofMinutes(30), Duration.ZERO)); // 5-field Unix cron
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("nightly", 500, 10, Duration.ofMinutes(30), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> props(null, 500, 10, Duration.ofMinutes(30), Duration.ZERO));
    }

    @Test
    void zoneIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ReconciliationProperties("0 0 2 * * *", null, 500, 10,
                Duration.ofMinutes(30), Duration.ZERO));
    }

    @Test
    void batchSizeMustBeWithinBounds() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("0 0 2 * * *", 0, 10, Duration.ofMinutes(30), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> props("0 0 2 * * *",
                ReconciliationProperties.MAX_BATCH_SIZE + 1, 10, Duration.ofMinutes(30), Duration.ZERO));
    }

    @Test
    void listedMismatchesMustBeWithinBounds() {
        assertThat(props("0 0 2 * * *", 500, 0, Duration.ofMinutes(30), Duration.ZERO).maxListedMismatches())
                .isZero();
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("0 0 2 * * *", 500, -1, Duration.ofMinutes(30), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> props("0 0 2 * * *", 500,
                ReconciliationProperties.MAX_LISTED_MISMATCHES + 1, Duration.ofMinutes(30), Duration.ZERO));
    }

    @Test
    void lockTimesMustBeConsistent() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("0 0 2 * * *", 500, 10, Duration.ZERO, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("0 0 2 * * *", 500, 10, null, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("0 0 2 * * *", 500, 10, Duration.ofMinutes(5), Duration.ofMinutes(6)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> props("0 0 2 * * *", 500, 10, Duration.ofMinutes(5), Duration.ofSeconds(-1)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReconciliationProperties.class)
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "trustledger.reconciliation.cron=0 0 2 * * *",
                    "trustledger.reconciliation.zone=Asia/Kolkata",
                    "trustledger.reconciliation.batch-size=500",
                    "trustledger.reconciliation.max-listed-mismatches=1000",
                    "trustledger.reconciliation.lock-at-most-for=30m",
                    "trustledger.reconciliation.lock-at-least-for=1m");

    @Test
    void bindsTheApplicationYmlFormat() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ReconciliationProperties bound = context.getBean(ReconciliationProperties.class);
            assertThat(bound.zone()).isEqualTo(IST);
            assertThat(bound.lockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
            assertThat(bound.lockAtLeastFor()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void invalidValueFailsStartup() {
        runner.withPropertyValues("trustledger.reconciliation.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
