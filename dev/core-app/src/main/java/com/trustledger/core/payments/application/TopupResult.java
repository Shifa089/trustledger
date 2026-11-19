package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A top-up as the outside sees it. The {@link Topup} entity never leaves the module.
 *
 * @param keyId the gateway's public checkout key ID, which the browser needs to open the checkout
 */
public record TopupResult(UUID topupId, TopupStatus status, UUID walletId, Money amount, String gatewayOrderId,
        String gatewayPaymentId, String keyId, Instant createdAt, Instant updatedAt) {

    static TopupResult of(Topup topup, String keyId) {
        return new TopupResult(topup.getId(), topup.getStatus(), topup.getWalletId(), topup.amount(),
                topup.getGatewayOrderId(), topup.getGatewayPaymentId(), keyId, topup.getCreatedAt(),
                topup.getUpdatedAt());
    }
}
