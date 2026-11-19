package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.Money;
import java.util.Objects;

/**
 * One payment attempt at the gateway.
 *
 * @param paymentId gateway payment ID ({@code pay_...})
 * @param orderId   order it belongs to; may be {@code null} for payments made without an order
 * @param amount    amount of the attempt
 * @param status    mapped status; {@link GatewayPaymentStatus#UNKNOWN} for a status we do not know
 * @param errorCode gateway error code of a failed attempt (e.g. {@code BAD_REQUEST_ERROR}), else {@code null}.
 *                  Never contains personal data.
 */
public record GatewayPayment(String paymentId, String orderId, Money amount, GatewayPaymentStatus status,
        String errorCode) {

    public GatewayPayment {
        PaymentGateway.requirePaymentId(paymentId);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
    }
}
