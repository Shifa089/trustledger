package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;

/**
 * The gateway refused the request with a 4xx (bad credentials, unknown ID, invalid field). 502 GATEWAY_REJECTED.
 * Not retried and not counted by the circuit breaker: the gateway is up, our request is wrong.
 * <p>
 * {@link #gatewayErrorCode()} and {@link #httpStatus()} are for logs and for callers such as the reconciler
 * (e.g. "payment not found"); the client only sees the generic detail.
 */
public class GatewayRejectedException extends TrustLedgerException {

    public static final String DETAIL = "The payment gateway rejected the request.";

    private final int httpStatus;
    private final String gatewayErrorCode;

    public GatewayRejectedException(int httpStatus, String gatewayErrorCode) {
        super(ErrorCode.GATEWAY_REJECTED, DETAIL);
        this.httpStatus = httpStatus;
        this.gatewayErrorCode = gatewayErrorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Razorpay's {@code error.code}, e.g. {@code BAD_REQUEST_ERROR}; {@code null} if the body had none. */
    public String gatewayErrorCode() {
        return gatewayErrorCode;
    }
}
