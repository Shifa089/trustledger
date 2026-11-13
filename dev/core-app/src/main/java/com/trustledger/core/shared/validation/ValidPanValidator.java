package com.trustledger.core.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class ValidPanValidator implements ConstraintValidator<ValidPan, String> {

    /** 4th character = holder type: A, B, C, F, G, H, L, J, P (individual), T. */
    private static final Pattern PAN = Pattern.compile("[A-Z]{3}[ABCFGHLJPT][A-Z][0-9]{4}[A-Z]");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PAN.matcher(value).matches();
    }
}
