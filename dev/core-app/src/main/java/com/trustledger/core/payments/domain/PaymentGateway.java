package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.Money;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Port to the payment gateway (Razorpay in test mode, FR-P-4 and FR-P-6). The adapter in {@code infrastructure}
 * owns HTTP, timeouts, retries and the circuit breaker; callers see only these records and two exceptions:
 * <ul>
 *   <li>{@link GatewayUnavailableException} (503): timeout, 5xx, network error, garbled response or open circuit.
 *       Trying again later may work.</li>
 *   <li>{@link GatewayRejectedException} (502): the gateway refused the request (4xx). Trying again will not help.</li>
 * </ul>
 * Never call it while holding database row locks (CLAUDE.md, SPEC §3 rule 6).
 */
public interface PaymentGateway {

    /** Gateway order IDs, e.g. {@code order_EKwxwAgItmmXdp}. */
    Pattern ORDER_ID = Pattern.compile("order_[A-Za-z0-9]{1,40}");

    /** Gateway payment IDs, e.g. {@code pay_29QQoUBi66xm2f}. */
    Pattern PAYMENT_ID = Pattern.compile("pay_[A-Za-z0-9]{1,40}");

    /**
     * Creates an order for the customer to pay. Never retried: the gateway does not de-duplicate orders, so a
     * retry after a timeout could create a second one. An order nobody pays simply expires.
     *
     * @param amount  positive INR amount
     * @param receipt our reference (the top-up ID), at most 40 characters
     */
    GatewayOrder createOrder(Money amount, String receipt);

    /** Reads one payment. Safe to retry. */
    GatewayPayment fetchPayment(String paymentId);

    /** Reads all payment attempts for an order (FR-P-6 reconciliation); empty if none. Safe to retry. */
    List<GatewayPayment> fetchOrderPayments(String orderId);

    static String requireOrderId(String orderId) {
        if (orderId == null || !ORDER_ID.matcher(orderId).matches()) {
            throw new IllegalArgumentException("Not a gateway order ID");
        }
        return orderId;
    }

    static String requirePaymentId(String paymentId) {
        if (paymentId == null || !PAYMENT_ID.matcher(paymentId).matches()) {
            throw new IllegalArgumentException("Not a gateway payment ID");
        }
        return paymentId;
    }
}
