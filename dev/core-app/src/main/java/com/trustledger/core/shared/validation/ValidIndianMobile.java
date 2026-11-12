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

/** Indian mobile: exactly 10 digits starting with 6-9, no country code. {@code null} is valid. */
@Documented
@Constraint(validatedBy = ValidIndianMobileValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, TYPE_USE})
@Retention(RUNTIME)
public @interface ValidIndianMobile {

    String message() default "must be a 10-digit Indian mobile number starting with 6-9";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
