package com.trustledger.core.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the custom constraints through a real Jakarta Validator (Hibernate Validator). */
class ValidatorsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    record PanHolder(@ValidPan String pan) {
    }

    record MobileHolder(@ValidIndianMobile String mobile) {
    }

    record AmountHolder(@ValidAmount String amount) {
    }

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> Set<ConstraintViolation<T>> validate(T bean) {
        return validator.validate(bean);
    }

    @Nested
    class Pan {

        @ParameterizedTest
        @ValueSource(strings = {"ABCPE1234F", "AAACA1234Z", "XYZTB0000A"})
        void accepts(String pan) {
            assertThat(validate(new PanHolder(pan))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "abcpe1234f", "ABCDE1234F", "ABCPE12345", "ABCP1234F", "ABCPE1234FF",
                " ABCPE1234F", "ABC9E1234F"})
        void rejects(String pan) {
            assertThat(validate(new PanHolder(pan)))
                    .singleElement()
                    .extracting(ConstraintViolation::getMessage)
                    .isEqualTo("must be a valid PAN");
        }

        @Test
        void nullIsLeftToNotNull() {
            assertThat(validate(new PanHolder(null))).isEmpty();
        }
    }

    @Nested
    class Mobile {

        @ParameterizedTest
        @ValueSource(strings = {"9876543210", "6000000000", "7123456789", "8999999999"})
        void accepts(String mobile) {
            assertThat(validate(new MobileHolder(mobile))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "5876543210", "0987654321", "987654321", "98765432101", "+919876543210",
                "98765 43210", "98765-43210", "abcdefghij"})
        void rejects(String mobile) {
            assertThat(validate(new MobileHolder(mobile))).hasSize(1);
        }

        @Test
        void nullIsLeftToNotNull() {
            assertThat(validate(new MobileHolder(null))).isEmpty();
        }
    }

    @Nested
    class Amount {

        @ParameterizedTest
        @ValueSource(strings = {"1500.50", "1500.5", "1500", "0.01", "1", "92233720368547758.07"})
        void accepts(String amount) {
            assertThat(validate(new AmountHolder(amount))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "1.005", "-1", "+1", "1e3", "1,500", ".5", "1.", " 1", "",
                "abc", "92233720368547758.08"})
        void rejects(String amount) {
            assertThat(validate(new AmountHolder(amount)))
                    .singleElement()
                    .extracting(ConstraintViolation::getMessage)
                    .isEqualTo("must be a positive amount with at most 2 decimal places");
        }

        @Test
        void nullIsLeftToNotNull() {
            assertThat(validate(new AmountHolder(null))).isEmpty();
        }
    }
}
