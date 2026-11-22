package com.trustledger.aml.cases.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.aml.cases.*}: what the case endpoints show (FR-AML-4). Startup fails on unsafe values.
 *
 * @param factLookback how far before the case's first alert its facts start; the longest rule window (24 h), so
 *                     every fact a rule counted is shown
 * @param maxFacts     facts shown at most per case (and in its STR report)
 * @param maxPageSize  cases per page at most in the case list
 */
@ConfigurationProperties("trustledger.aml.cases")
public record CaseProperties(Duration factLookback, int maxFacts, int maxPageSize) {

    public CaseProperties {
        Objects.requireNonNull(factLookback, "trustledger.aml.cases.fact-lookback is required");
        if (factLookback.isNegative() || factLookback.compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("trustledger.aml.cases.fact-lookback must be 0..31 days");
        }
        if (maxFacts < 1 || maxFacts > 10_000) {
            throw new IllegalArgumentException("trustledger.aml.cases.max-facts must be 1..10000");
        }
        if (maxPageSize < 1 || maxPageSize > 500) {
            throw new IllegalArgumentException("trustledger.aml.cases.max-page-size must be 1..500");
        }
    }
}
