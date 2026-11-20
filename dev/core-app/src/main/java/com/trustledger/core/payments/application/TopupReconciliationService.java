package com.trustledger.core.payments.application;

import com.trustledger.core.payments.application.TopupSettlementService.DueTopup;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.GatewayRejectedException;
import com.trustledger.core.payments.domain.GatewayUnavailableException;
import com.trustledger.core.payments.domain.PaymentGateway;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-P-6: asks the gateway about top-ups still PENDING after {@code pending-after} (15 minutes), in case the webhook
 * never arrived.
 * <ol>
 *   <li>Read the oldest due top-ups in a short read-only transaction ({@link TopupSettlementService#duePending}).</li>
 *   <li>For each, {@link PaymentGateway#fetchOrderPayments} with <em>no</em> transaction open and no lock held.</li>
 *   <li>A captured payment → {@link TopupSettlementService#capture} (its own transaction; the same code as the
 *       webhook, so a race with it credits once). Nothing captured and older than {@code expire-after} (24 hours)
 *       → {@link TopupSettlementService#expire}. Otherwise leave it for the next run.</li>
 * </ol>
 * A gateway error skips that top-up only; it is tried again next run. {@code Propagation.NEVER} makes "no remote
 * call inside a transaction" a check rather than a hope.
 */
@Service
public class TopupReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(TopupReconciliationService.class);

    private final TopupSettlementService settlement;
    private final PaymentGateway gateway;
    private final TopupReconcilerProperties properties;
    private final Clock clock;

    TopupReconciliationService(TopupSettlementService settlement, PaymentGateway gateway,
            TopupReconcilerProperties properties, Clock clock) {
        this.settlement = settlement;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public TopupReconciliationReport reconcile() {
        Instant now = clock.instant();
        Instant expireBefore = now.minus(properties.expireAfter());
        List<DueTopup> due = settlement.duePending(now.minus(properties.pendingAfter()), properties.batchSize());

        int captured = 0;
        int parked = 0;
        int expired = 0;
        int stillPending = 0;
        int skipped = 0;
        int failed = 0;
        for (DueTopup topup : due) {
            List<GatewayPayment> payments;
            try {
                payments = gateway.fetchOrderPayments(topup.gatewayOrderId());
            } catch (GatewayUnavailableException | GatewayRejectedException e) {
                skipped++;
                log.warn("Reconciler skipped top-up {}: gateway lookup failed ({})", topup.topupId(), e.errorCode());
                continue;
            }
            Optional<GatewayPayment> paid = payments.stream()
                    .filter(p -> p.status() == GatewayPaymentStatus.CAPTURED)
                    .findFirst();
            try {
                SettlementOutcome outcome;
                if (paid.isPresent()) {
                    // The lookup was for this order; payments in an order collection always carry it.
                    GatewayPayment payment = paid.get();
                    outcome = settlement.capture(payment.orderId() != null ? payment
                            : new GatewayPayment(payment.paymentId(), topup.gatewayOrderId(), payment.amount(),
                                    payment.status(), payment.errorCode()));
                } else if (topup.createdAt().isBefore(expireBefore)) {
                    outcome = settlement.expire(topup.topupId());
                } else {
                    outcome = null;
                }
                if (outcome == null) {
                    stillPending++;
                } else {
                    switch (outcome) {
                        case CAPTURED -> captured++;
                        case PARKED -> parked++;
                        case EXPIRED -> expired++;
                        case FAILED, IGNORED -> { } // settled elsewhere meanwhile, or a logged mismatch
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Reconciler could not settle top-up {}", topup.topupId(), e);
            }
        }
        TopupReconciliationReport report = new TopupReconciliationReport(due.size(), captured, parked, expired,
                stillPending, skipped, failed);
        if (!due.isEmpty()) {
            log.info("Pending top-up reconciliation: {}", report);
        }
        return report;
    }
}
