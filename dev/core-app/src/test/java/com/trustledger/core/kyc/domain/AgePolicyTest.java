package com.trustledger.core.kyc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AgePolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @Test
    void eighteenthBirthdayTodayIsOldEnough() {
        assertThat(AgePolicy.isAtLeast(18, LocalDate.of(2008, 11, 15), TODAY)).isTrue();
    }

    @Test
    void oneDayBeforeTheEighteenthBirthdayIsTooYoung() {
        assertThat(AgePolicy.isAtLeast(18, LocalDate.of(2008, 11, 16), TODAY)).isFalse();
    }

    @Test
    void clearlyAdultAndClearlyMinor() {
        assertThat(AgePolicy.isAtLeast(18, LocalDate.of(1970, 1, 1), TODAY)).isTrue();
        assertThat(AgePolicy.isAtLeast(18, LocalDate.of(2015, 6, 1), TODAY)).isFalse();
    }

    @Test
    void leapDayBirthdayCountsFromTwentyEighthFebruaryInCommonYears() {
        LocalDate leapDay = LocalDate.of(2008, 2, 29);

        assertThat(AgePolicy.isAtLeast(18, leapDay, LocalDate.of(2026, 2, 27))).isFalse();
        assertThat(AgePolicy.isAtLeast(18, leapDay, LocalDate.of(2026, 2, 28))).isTrue();
    }

    @Test
    void rejectsBadArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> AgePolicy.isAtLeast(-1, TODAY, TODAY));
        assertThatNullPointerException().isThrownBy(() -> AgePolicy.isAtLeast(18, null, TODAY));
        assertThatNullPointerException().isThrownBy(() -> AgePolicy.isAtLeast(18, TODAY, null));
    }
}
