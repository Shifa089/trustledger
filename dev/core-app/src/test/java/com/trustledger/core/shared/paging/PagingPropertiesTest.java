package com.trustledger.core.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Day 14 B, finding L2: page sizes come from {@code trustledger.paging}, not from constants in the services. */
class PagingPropertiesTest {

    private final PagingProperties paging = new PagingProperties(20, 100);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PagingConfig.class);

    @Test
    void missingSizeUsesTheDefault() {
        assertThat(paging.checkedSize(0, null)).isEqualTo(20);
    }

    @Test
    void sizesWithinTheBoundsArePassedThrough() {
        assertThat(paging.checkedSize(0, 1)).isEqualTo(1);
        assertThat(paging.checkedSize(7, 100)).isEqualTo(100);
    }

    @Test
    void outOfBoundsRequestsAreValidationErrors() {
        for (int[] pageAndSize : new int[][] {{0, 0}, {0, 101}, {-1, 20}, {0, -5}}) {
            assertThatThrownBy(() -> paging.checkedSize(pageAndSize[0], pageAndSize[1]))
                    .isInstanceOfSatisfying(TrustLedgerException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                    .hasMessageContaining("size 1-100");
        }
    }

    @Test
    void unsafeConfigurationFailsFast() {
        assertThatThrownBy(() -> new PagingProperties(20, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PagingProperties(20, PagingProperties.MAX_MAX_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PagingProperties(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PagingProperties(101, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindsFromConfiguration() {
        runner.withPropertyValues("trustledger.paging.default-size=10", "trustledger.paging.max-size=50")
                .run(context -> assertThat(context.getBean(PagingProperties.class))
                        .isEqualTo(new PagingProperties(10, 50)));
    }

    @Test
    void startupFailsOnABadValue() {
        runner.withPropertyValues("trustledger.paging.default-size=10", "trustledger.paging.max-size=5000")
                .run(context -> assertThat(context).hasFailed());
    }
}
