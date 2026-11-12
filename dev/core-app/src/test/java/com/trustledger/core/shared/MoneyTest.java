package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency INR = Money.INR;
    private static final Currency USD = Currency.getInstance("USD");

    private static Money inr(long paise) {
        return Money.of(paise, INR);
    }

    @Nested
    class Construction {

        @Test
        void createsFromMinorUnits() {
            Money money = Money.of(150050, INR);
            assertThat(money.amountMinor()).isEqualTo(150050);
            assertThat(money.currency()).isEqualTo(INR);
        }

        @Test
        void zeroIsZero() {
            assertThat(Money.zero(INR).isZero()).isTrue();
            assertThat(inr(1).isZero()).isFalse();
        }

        @Test
        void rejectsNegativeAmount() {
            assertThatThrownBy(() -> Money.of(-1, INR)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNullCurrency() {
            assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsCurrencyWithoutTwoMinorDigits() {
            assertThatThrownBy(() -> Money.of(1, Currency.getInstance("JPY")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void equalityIsByValue() {
            assertThat(inr(100)).isEqualTo(inr(100)).hasSameHashCodeAs(inr(100));
            assertThat(inr(100)).isNotEqualTo(Money.of(100, USD));
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void adds() {
            assertThat(inr(150050).plus(inr(50))).isEqualTo(inr(150100));
        }

        @Test
        void subtracts() {
            assertThat(inr(150050).minus(inr(50))).isEqualTo(inr(150000));
            assertThat(inr(50).minus(inr(50)).isZero()).isTrue();
        }

        @Test
        void additionOverflowThrows() {
            assertThatThrownBy(() -> inr(Long.MAX_VALUE).plus(inr(1))).isInstanceOf(ArithmeticException.class);
        }

        @Test
        void subtractionBelowZeroThrows() {
            assertThatThrownBy(() -> inr(10).minus(inr(11))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void currencyMismatchThrows() {
            Money usd = Money.of(1, USD);
            assertThatThrownBy(() -> inr(1).plus(usd)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> inr(1).minus(usd)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> inr(1).isGreaterThan(usd)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Comparison {

        @Test
        void compares() {
            assertThat(inr(2).isGreaterThan(inr(1))).isTrue();
            assertThat(inr(1).isGreaterThan(inr(1))).isFalse();
            assertThat(inr(1).isGreaterThanOrEqualTo(inr(1))).isTrue();
            assertThat(inr(0).isGreaterThanOrEqualTo(inr(1))).isFalse();
            assertThat(inr(1).isLessThan(inr(2))).isTrue();
            assertThat(inr(2).isLessThan(inr(2))).isFalse();
            assertThat(inr(1).compareTo(inr(1))).isZero();
        }
    }

    @Nested
    class Parsing {

        @ParameterizedTest
        @CsvSource({
                "1500.50, 150050",
                "1500.5, 150050",
                "1500, 150000",
                "0.01, 1",
                "0, 0",
                "0.00, 0",
                "007.10, 710",
                "92233720368547758.07, 9223372036854775807"
        })
        void parsesValidAmounts(String input, long expectedPaise) {
            Money money = Money.parse(input);
            assertThat(money.amountMinor()).isEqualTo(expectedPaise);
            assertThat(money.currency()).isEqualTo(INR);
        }

        @Test
        void parsesWithExplicitCurrency() {
            assertThat(Money.parse("1.25", USD)).isEqualTo(Money.of(125, USD));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {
                "1.005", "-1", "-0.01", "+1", " ", " 1", "1 ", "abc", "1e3", "1,500", ".5", "1.", "1..0",
                "١٢", "92233720368547758.08", "99999999999999999999"
        })
        void rejectsInvalidAmountsWithoutRounding(String input) {
            assertThatThrownBy(() -> Money.parse(input))
                    .isInstanceOf(TrustLedgerException.class)
                    .extracting(e -> ((TrustLedgerException) e).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Nested
    class Formatting {

        @ParameterizedTest
        @CsvSource({"150050, 1500.50", "150000, 1500.00", "5, 0.05", "0, 0.00", "10, 0.10"})
        void formatsWithTwoDecimals(long paise, String expected) {
            assertThat(inr(paise).toDecimalString()).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1500.50", "0.01", "0.00", "92233720368547758.07"})
        void roundTrips(String value) {
            assertThat(Money.parse(value).toDecimalString()).isEqualTo(value);
        }
    }
}
