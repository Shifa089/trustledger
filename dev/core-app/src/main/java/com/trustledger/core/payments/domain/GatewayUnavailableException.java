package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;

/**
 * The gateway could not be reached in time or answered with a server error, or the circuit breaker is open.
 * 503 GATEWAY_UNAVAILABLE; the client may try again later. Counted as a failure by the circuit breaker.
 */
public class GatewayUnavailableException extends TrustLedgerException {

    public static final String DETAIL = "The payment gateway is temporarily unavailable. Please try again later.";

    public GatewayUnavailableException(Throwable cause) {
        super(ErrorCode.GATEWAY_UNAVAILABLE, DETAIL);
        if (cause != null) {
            initCause(cause);
        }
    }
}
