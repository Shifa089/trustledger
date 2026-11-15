package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ErrorCodeTest {

    /** The error code table from SPEC §5, plus the two framework-level codes. */
    @ParameterizedTest
    @CsvSource({
            "VALIDATION_FAILED, 400",
            "UNAUTHENTICATED, 401",
            "ACCESS_DENIED, 403",
            "KYC_REQUIRED, 403",
            "NOT_FOUND, 404",
            "REQUEST_IN_PROGRESS, 409",
            "MAKER_CHECKER_VIOLATION, 409",
            "INVALID_STATE_TRANSITION, 409",
            "KYC_ALREADY_SUBMITTED, 409",
            "PHONE_ALREADY_REGISTERED, 409",
            "AGE_REQUIREMENT_NOT_MET, 422",
            "KYC_DOCUMENTS_MISSING, 409",
            "CONCURRENT_MODIFICATION, 409",
            "WALLET_FROZEN, 409",
            "DUPLICATE_POSTING, 409",
            "DOCUMENT_TOO_LARGE, 413",
            "UNSUPPORTED_DOCUMENT_TYPE, 415",
            "INSUFFICIENT_FUNDS, 422",
            "LIMIT_EXCEEDED, 422",
            "IDEMPOTENCY_KEY_REUSED, 422",
            "RATE_LIMITED, 429",
            "GATEWAY_UNAVAILABLE, 503",
            "STORAGE_UNAVAILABLE, 503",
            "NOT_IMPLEMENTED, 501",
            "UNSUPPORTED_REQUEST, 400",
            "INTERNAL_ERROR, 500"
    })
    void mapsToSpecStatus(ErrorCode code, int status) {
        assertThat(code.status().value()).isEqualTo(status);
    }

    @Test
    void typeIsKebabCaseUnderErrorsBase() {
        assertThat(ErrorCode.INSUFFICIENT_FUNDS.type())
                .hasToString("https://trustledger.dev/errors/insufficient-funds");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeHasATitle(ErrorCode code) {
        assertThat(code.title()).isNotBlank();
    }
}
