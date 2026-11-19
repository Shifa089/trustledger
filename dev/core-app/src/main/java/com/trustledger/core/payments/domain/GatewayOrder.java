package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.Money;
import java.util.Objects;

/**
 * An order created at the gateway.
 *
 * @param orderId gateway order ID ({@code order_...})
 * @param amount  amount the customer is asked to pay
 * @param receipt our reference that was sent with the order
 * @param status  gateway order status as reported ({@code created}, {@code attempted}, {@code paid})
 */
public record GatewayOrder(String orderId, Money amount, String receipt, String status) {

    public GatewayOrder {
        PaymentGateway.requireOrderId(orderId);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(status, "status");
    }
}
