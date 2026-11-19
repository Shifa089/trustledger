package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.GatewayOrder;
import com.trustledger.core.payments.domain.GatewayRejectedException;
import com.trustledger.core.payments.domain.GatewayUnavailableException;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a top-up (FR-P-4, {@code POST /topups}): three steps, and the remote call sits between two transactions,
 * never inside one.
 * <ol>
 *   <li>{@link TopupWriter#open}: limits checked under the wallet lock, CREATED saved, commit.</li>
 *   <li>{@link PaymentGateway#createOrder}: no transaction, no lock. The receipt is the top-up ID.</li>
 *   <li>{@link TopupWriter#markPending} with the order ID, commit.</li>
 * </ol>
 * If the gateway fails, the top-up is marked FAILED in its own transaction and the error is rethrown (503 or 502).
 * The idempotency interceptor then releases the key, so the customer can retry with the same key; that retry
 * creates a new top-up and the FAILED one stays as history. A timed-out order may still exist at the gateway, but
 * nobody ever learns its ID, so it is never paid and simply expires there.
 * <p>
 * {@code Propagation.NEVER} turns the rule into a check: calling this inside a transaction fails at once instead of
 * quietly holding the wallet lock across a three-second remote call.
 */
@Service
public class TopupService {

    private static final Logger log = LoggerFactory.getLogger(TopupService.class);

    private final TopupWriter writer;
    private final PaymentGateway gateway;

    TopupService(TopupWriter writer, PaymentGateway gateway) {
        this.writer = writer;
        this.gateway = gateway;
    }

    /**
     * @return the PENDING top-up with the gateway order ID and the checkout key ID
     * @throws TrustLedgerException from {@link TopupWriter#open}, or {@code GATEWAY_UNAVAILABLE} (503) /
     *         {@code GATEWAY_REJECTED} (502) when the order could not be created
     */
    @Transactional(propagation = Propagation.NEVER)
    public TopupResult create(UUID userId, Money amount) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(amount, "amount");
        Topup created = writer.open(userId, amount);
        UUID topupId = created.getId();

        GatewayOrder order;
        try {
            order = gateway.createOrder(created.amount(), topupId.toString());
        } catch (GatewayUnavailableException | GatewayRejectedException e) {
            try {
                writer.markFailed(topupId);
            } catch (RuntimeException markFailure) {
                // Keep the gateway error as the answer; the row stays CREATED and the reconciler cleans it up.
                e.addSuppressed(markFailure);
            }
            log.warn("Top-up {} failed: the gateway order could not be created ({})", topupId, e.errorCode());
            throw e;
        }

        Topup pending = writer.markPending(topupId, order.orderId());
        log.info("Top-up {} pending, gateway order {}", topupId, order.orderId());
        return TopupResult.of(pending, gateway.checkoutKeyId());
    }
}
