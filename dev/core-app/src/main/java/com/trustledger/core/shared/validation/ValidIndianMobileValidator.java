package com.trustledger.core.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class ValidIndianMobileValidator implements ConstraintValidator<ValidIndianMobile, String> {

    private static final Pattern MOBILE = Pattern.compile("[6-9][0-9]{9}");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || MOBILE.matcher(value).matches();
    }
}
