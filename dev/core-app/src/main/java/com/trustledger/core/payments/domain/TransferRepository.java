package com.trustledger.core.payments.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Port for transfers; implemented by Spring Data in {@code payments.infrastructure}. */
public interface TransferRepository {

    Transfer save(Transfer transfer);

    Transfer saveAndFlush(Transfer transfer);

    Optional<Transfer> findById(UUID id);

    /**
     * The transfer with its row locked ({@code SELECT ... FOR UPDATE}) until the transaction ends. Used by the
     * compliance review so two officers deciding the same held transfer are serialised. Lock order: the transfer
     * row first, then the wallets (ADR 0004).
     */
    Optional<Transfer> findByIdForUpdate(UUID id);

    /** Transfers in one status; the caller supplies the sort (the review queue is oldest first). */
    Page<Transfer> findByStatus(TransferStatus status, Pageable pageable);

    /**
     * Sum in minor units of the sender's transfers created at or after {@code fromInclusive} with one of the
     * statuses (0 if none). Uses the {@code (sender_wallet_id, created_at)} index. For a limit check to be
     * consistent, the caller holds the sender wallet's row lock (see {@code LimitChecker}).
     */
    long sumOutgoingSince(UUID senderWalletId, Instant fromInclusive, Collection<TransferStatus> statuses);
}
