package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TopupReconcilerPropertiesTest {

    private static final String CRON = "0 */5 * * * *";
    private static final Duration PENDING = Duration.ofMinutes(15);
    private static final Duration EXPIRE = Duration.ofHours(24);
    private static final Duration LOCK = Duration.ofMinutes(10);

    @Test
    void theApplicationDefaultsAreValid() {
        assertThatCode(() -> new TopupReconcilerProperties(CRON, PENDING, EXPIRE, 100, LOCK))
                .doesNotThrowAnyException();
    }

    @Test
    void cronMustBeValid() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(null, PENDING, EXPIRE, 100, LOCK));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties("*/5 * * * *", PENDING, EXPIRE, 100, LOCK));
    }

    @Test
    void pendingAfterIsAtLeastAMinute() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, Duration.ofSeconds(59), EXPIRE, 100, LOCK));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, null, EXPIRE, 100, LOCK));
    }

    @Test
    void expireAfterIsLongerThanPendingAfter() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, PENDING, PENDING, 100, LOCK));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, PENDING, null, 100, LOCK));
    }

    @Test
    void batchSizeIsBounded() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, PENDING, EXPIRE, 0, LOCK));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, PENDING, EXPIRE, 1_001, LOCK));
    }

    @Test
    void lockAtMostForIsPositive() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, PENDING, EXPIRE, 100, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TopupReconcilerProperties(CRON, PENDING, EXPIRE, 100, null));
    }
}
