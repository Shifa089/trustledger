package com.trustledger.core.shared.validation;

import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidAmountValidator implements ConstraintValidator<ValidAmount, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            return !Money.parse(value).isZero();
        } catch (TrustLedgerException e) {
            return false;
        }
    }
}
