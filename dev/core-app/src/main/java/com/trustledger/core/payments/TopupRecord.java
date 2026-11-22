package com.trustledger.core.payments;

import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A top-up as another module may see it (see {@link PaymentsApi#topupsOf}). {@code status} is the payments
 * module's top-up status name, e.g. {@code CAPTURED}. The gateway IDs are Razorpay's order and payment references
 * (each {@code null} until the gateway has issued it), useful to the customer when raising a dispute.
 */
public record TopupRecord(UUID topupId, UUID walletId, Money amount, String status, String gatewayOrderId,
        String gatewayPaymentId, Instant createdAt, Instant updatedAt) {
}
