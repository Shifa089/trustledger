package com.trustledger.core.wallet.domain;

import com.trustledger.core.wallet.FreezeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * One freeze of a wallet (FR-W-3), kept for audits. Created by {@link Wallet#freeze} and closed by
 * {@link Wallet#unfreeze}; never changed otherwise. The reason is free text written by an officer and may
 * quote personal data, so it is never logged.
 */
@Entity
@Table(name = "wallet_freezes", schema = "wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletFreeze {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Getter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private FreezeSource source;

    @Column(name = "reason", nullable = false, updatable = false, length = Wallet.MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "frozen_by", updatable = false)
    private UUID frozenBy;

    @Column(name = "frozen_at", nullable = false, updatable = false)
    private Instant frozenAt;

    @Column(name = "unfrozen_by")
    private UUID unfrozenBy;

    @Column(name = "unfrozen_at")
    private Instant unfrozenAt;

    @Column(name = "unfreeze_reason", length = Wallet.MAX_REASON_LENGTH)
    private String unfreezeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    static WalletFreeze start(Wallet wallet, FreezeSource source, String reason, UUID frozenBy, Instant now) {
        WalletFreeze freeze = new WalletFreeze();
        freeze.wallet = Objects.requireNonNull(wallet, "wallet");
        freeze.source = Objects.requireNonNull(source, "source");
        freeze.reason = Objects.requireNonNull(reason, "reason");
        freeze.frozenBy = frozenBy;
        freeze.frozenAt = Objects.requireNonNull(now, "now");
        freeze.createdAt = now;
        return freeze;
    }

    void end(UUID unfrozenBy, String unfreezeReason, Instant now) {
        if (!isOpen()) {
            throw new IllegalStateException("Freeze " + id + " is already closed");
        }
        Objects.requireNonNull(now, "now");
        if (now.isBefore(frozenAt)) {
            throw new IllegalArgumentException("A freeze cannot end before it started");
        }
        this.unfrozenBy = Objects.requireNonNull(unfrozenBy, "unfrozenBy");
        this.unfreezeReason = Objects.requireNonNull(unfreezeReason, "unfreezeReason");
        this.unfrozenAt = now;
    }

    public boolean isOpen() {
        return unfrozenAt == null;
    }

    public UUID walletId() {
        return wallet.getId();
    }
}
