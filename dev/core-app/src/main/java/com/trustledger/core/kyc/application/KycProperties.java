package com.trustledger.core.kyc.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.kyc.*}.
 *
 * @param minAgeYears minimum age for KYC (FR-KYC-1). Must be 18-100: a value below the legal age of
 *                    majority would be a compliance bug, so startup fails rather than accept it.
 */
@ConfigurationProperties("trustledger.kyc")
public record KycProperties(int minAgeYears) {

    static final int LEGAL_MIN_AGE = 18;
    static final int MAX_AGE = 100;

    public KycProperties {
        if (minAgeYears < LEGAL_MIN_AGE || minAgeYears > MAX_AGE) {
            throw new IllegalArgumentException(
                    "trustledger.kyc.min-age-years must be " + LEGAL_MIN_AGE + "-" + MAX_AGE + ", was " + minAgeYears);
        }
    }
}
