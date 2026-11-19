package com.trustledger.core.payments.domain;

import java.util.Locale;

/** Payment status at the gateway (Razorpay's lower-case {@code status} field). */
public enum GatewayPaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED,
    /** A status this code does not know yet. Treated as "not settled": never credits a wallet. */
    UNKNOWN;

    /** Maps the gateway's value; {@code null} or an unknown value gives {@link #UNKNOWN}, never an exception. */
    public static GatewayPaymentStatus fromGateway(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "created" -> CREATED;
            case "authorized" -> AUTHORIZED;
            case "captured" -> CAPTURED;
            case "refunded" -> REFUNDED;
            case "failed" -> FAILED;
            default -> UNKNOWN;
        };
    }
}
