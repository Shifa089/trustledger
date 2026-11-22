package com.trustledger.core.payments.domain;

import com.trustledger.core.payments.WalletTransactionType;
import com.trustledger.core.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A row of the {@code payments.wallet_transactions} view (V25): one wallet's view of a transfer or a top-up, for
 * the transaction history (FR-S-1). Read-only ({@link Immutable}); the state lives in {@link Transfer} and
 * {@link Topup}.
 */
@Entity
@Immutable
@IdClass(WalletTransactionId.class)
@Table(name = "wallet_transactions", schema = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTransaction {

    @Id
    @Column(name = "wallet_id")
    private UUID walletId;

    @Id
    @Column(name = "source_id")
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20)
    private WalletTransactionType type;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "amount_minor")
    private long amountMinor;

    @Column(name = "currency", length = 3)
    private Currency currency;

    @Column(name = "counterparty_wallet_id")
    private UUID counterpartyWalletId;

    @Column(name = "note", length = 140)
    private String note;

    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * Whether the wallet's owner may see the row (V25): a receiver sees a transfer only once money arrived, and a
     * CREATED top-up is never shown. Every history query filters on it.
     */
    @Column(name = "visible")
    private boolean visible;

    public Money amount() {
        return Money.of(amountMinor, currency);
    }
}
