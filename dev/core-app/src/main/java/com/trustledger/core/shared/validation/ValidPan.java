package com.trustledger.core.shared.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Indian PAN: 5 letters, 4 digits, 1 letter, upper-case; the 4th letter is a valid holder type.
 * {@code null} is valid; combine with {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = ValidPanValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, TYPE_USE})
@Retention(RUNTIME)
public @interface ValidPan {

    String message() default "must be a valid PAN";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
