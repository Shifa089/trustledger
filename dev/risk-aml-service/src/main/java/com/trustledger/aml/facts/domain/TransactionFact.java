package com.trustledger.aml.facts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One money movement seen from one wallet (SPEC §4 {@code aml.transaction_facts}); what the AML rules count and
 * sum. Immutable: built by {@link #outgoingTransfer}, {@link #incomingTransfer} or {@link #topup}, never changed
 * (the table is append-only too), so it needs no {@code @Version}.
 */
@Entity
@Table(name = "transaction_facts", schema = "aml")
public class TransactionFact {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "counterparty_wallet_id", updatable = false)
    private UUID counterpartyWalletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 3)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private FactSource source;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionFact() {
        // JPA
    }

    private TransactionFact(UUID eventId, UUID walletId, UUID counterpartyWalletId, Direction direction,
            FactSource source, UUID referenceId, long amountMinor, String currency, Instant occurredAt,
            Instant createdAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.counterpartyWalletId = counterpartyWalletId;
        this.direction = direction;
        this.source = source;
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId");
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        if (source == FactSource.TRANSFER && (counterpartyWalletId == null || counterpartyWalletId.equals(walletId))) {
            throw new IllegalArgumentException("A transfer fact needs a different counterparty wallet");
        }
    }

    /** The sender's side of a transfer. */
    public static TransactionFact outgoingTransfer(UUID eventId, UUID senderWalletId, UUID receiverWalletId,
            UUID transferId, long amountMinor, String currency, Instant occurredAt, Instant now) {
        return new TransactionFact(eventId, senderWalletId, receiverWalletId, Direction.OUT, FactSource.TRANSFER,
                transferId, amountMinor, currency, occurredAt, now);
    }

    /** The receiver's side of the same transfer. */
    public static TransactionFact incomingTransfer(UUID eventId, UUID receiverWalletId, UUID senderWalletId,
            UUID transferId, long amountMinor, String currency, Instant occurredAt, Instant now) {
        return new TransactionFact(eventId, receiverWalletId, senderWalletId, Direction.IN, FactSource.TRANSFER,
                transferId, amountMinor, currency, occurredAt, now);
    }

    /** Money added from outside: always IN, no counterparty wallet. */
    public static TransactionFact topup(UUID eventId, UUID walletId, UUID topupId, long amountMinor, String currency,
            Instant occurredAt, Instant now) {
        return new TransactionFact(eventId, walletId, null, Direction.IN, FactSource.TOPUP, topupId, amountMinor,
                currency, occurredAt, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public UUID getCounterpartyWalletId() {
        return counterpartyWalletId;
    }

    public Direction getDirection() {
        return direction;
    }

    public FactSource getSource() {
        return source;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
