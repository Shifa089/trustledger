package com.trustledger.core.payments.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Port for transfers; implemented by Spring Data in {@code payments.infrastructure}. */
public interface TransferRepository {

    Transfer save(Transfer transfer);

    Transfer saveAndFlush(Transfer transfer);

    Optional<Transfer> findById(UUID id);

    /**
     * Sum in minor units of the sender's transfers created at or after {@code fromInclusive} with one of the
     * statuses (0 if none). Uses the {@code (sender_wallet_id, created_at)} index. For a limit check to be
     * consistent, the caller holds the sender wallet's row lock (see {@code LimitChecker}).
     */
    long sumOutgoingSince(UUID senderWalletId, Instant fromInclusive, Collection<TransferStatus> statuses);
}
