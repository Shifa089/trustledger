package com.trustledger.core.payments.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * Money added to a wallet through the payment gateway (FR-P-4): the aggregate that guards the top-up's state machine.
 * <pre>
 * CREATED --markPending--&gt; PENDING --capture--&gt; CAPTURED
 *                                  --park-----&gt; PARKED    (paid, but the wallet could not take it: ADR 0006)
 *                                  --fail-----&gt; FAILED
 *                                  --expire---&gt; EXPIRED
 * CREATED --fail--------------------------------&gt; FAILED   (the gateway order could not be created)
 * FAILED, EXPIRED (with an order) --capture/park--&gt; CAPTURED/PARKED   (late capture)
 * </pre>
 * Any other step throws {@link ErrorCode#INVALID_STATE_TRANSITION}. Invariants, also enforced by database checks
 * ({@code V13}, {@code V15}): a positive INR amount, an order ID from PENDING on, and a payment ID and journal entry
 * exactly when CAPTURED or PARKED.
 * <p>
 * CREATED is committed <em>before</em> the gateway is called, so the gateway call never runs inside a database
 * transaction (CLAUDE.md: no remote calls while holding locks).
 */
@Entity
@Table(name = "topups", schema = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Topup {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "gateway_order_id", length = 64)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 64)
    private String gatewayPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TopupStatus status;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** A new CREATED top-up of a positive INR amount. Limits are the caller's job (they need the database). */
    public static Topup create(UUID walletId, Money amount, Instant now) {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(amount, "amount");
        if (!Money.INR.equals(amount.currency())) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Top-ups must be in INR.");
        }
        if (amount.isZero()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Amount must be greater than zero.");
        }
        Topup topup = new Topup();
        topup.walletId = walletId;
        topup.amountMinor = amount.amountMinor();
        topup.currency = amount.currency();
        topup.status = TopupStatus.CREATED;
        topup.createdAt = Objects.requireNonNull(now, "now");
        topup.updatedAt = now;
        return topup;
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    /** CREATED -> PENDING: the gateway order exists and the customer can pay it. */
    public void markPending(String gatewayOrderId, Instant now) {
        PaymentGateway.requireOrderId(gatewayOrderId);
        requireStatus("mark pending", TopupStatus.CREATED);
        this.gatewayOrderId = gatewayOrderId;
        moveTo(TopupStatus.PENDING, now);
    }

    /** CREATED or PENDING -> FAILED: the order could not be created, or the payment failed. */
    public void fail(Instant now) {
        if (status != TopupStatus.CREATED && status != TopupStatus.PENDING) {
            throw invalid("fail");
        }
        moveTo(TopupStatus.FAILED, now);
    }

    /**
     * PENDING (or, late, FAILED / EXPIRED) -> CAPTURED: the payment was captured and credited to the wallet
     * (GATEWAY_CLEARING -> WALLET posted).
     */
    public void capture(String gatewayPaymentId, UUID journalEntryId, Instant now) {
        settle("capture", gatewayPaymentId, journalEntryId, TopupStatus.CAPTURED, now);
    }

    /**
     * PENDING (or, late, FAILED / EXPIRED) -> PARKED: the payment was captured but the wallet could not take it, so
     * the money was booked to suspense (GATEWAY_CLEARING -> SUSPENSE posted). ADR 0006.
     */
    public void park(String gatewayPaymentId, UUID journalEntryId, Instant now) {
        settle("park", gatewayPaymentId, journalEntryId, TopupStatus.PARKED, now);
    }

    /** Whether a captured payment can still settle this top-up ({@link TopupStatus#SETTLEABLE}, with an order). */
    public boolean isSettleable() {
        return TopupStatus.SETTLEABLE.contains(status) && gatewayOrderId != null;
    }

    /** Whether a captured payment was already booked for it: CAPTURED or PARKED. */
    public boolean isSettled() {
        return TopupStatus.SETTLED.contains(status);
    }

    private void settle(String action, String gatewayPaymentId, UUID journalEntryId, TopupStatus target,
            Instant now) {
        PaymentGateway.requirePaymentId(gatewayPaymentId);
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        if (!isSettleable()) {
            throw invalid(action);
        }
        this.gatewayPaymentId = gatewayPaymentId;
        this.journalEntryId = journalEntryId;
        moveTo(target, now);
    }

    /** PENDING -> EXPIRED: nobody paid the order in time (FR-P-6). */
    public void expire(Instant now) {
        requireStatus("expire", TopupStatus.PENDING);
        moveTo(TopupStatus.EXPIRED, now);
    }

    /** Ownership check for {@code GET /topups/{id}}. */
    public boolean belongsTo(UUID walletId) {
        return this.walletId.equals(walletId);
    }

    private void requireStatus(String action, TopupStatus expected) {
        if (status != expected) {
            throw invalid(action);
        }
    }

    private TrustLedgerException invalid(String action) {
        return new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                "Cannot " + action + " a top-up in status " + status + ".");
    }

    private void moveTo(TopupStatus target, Instant now) {
        this.status = target;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }
}
