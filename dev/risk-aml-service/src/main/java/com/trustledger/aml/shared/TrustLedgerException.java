package com.trustledger.aml.shared;

import java.util.Objects;

/**
 * Base class for expected business errors (CLAUDE.md "Errors"). The {@code detail} is shown to the client, so it
 * must never contain personal data.
 */
public class TrustLedgerException extends RuntimeException {

    private final ErrorCode errorCode;

    public TrustLedgerException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String detail() {
        return getMessage();
    }
}
