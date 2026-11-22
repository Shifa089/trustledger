package com.trustledger.core.payments.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for top-ups; implemented by Spring Data in {@code payments.infrastructure}. */
public interface TopupRepository {

    Topup save(Topup topup);

    Topup saveAndFlush(Topup topup);

    Optional<Topup> findById(UUID id);

    /**
     * The top-up of a gateway order, row-locked ({@code SELECT ... FOR UPDATE}) until the transaction ends. Settling a
     * payment locks the top-up first, then its wallet; every settlement path takes them in that order.
     */
    Optional<Topup> findByGatewayOrderIdForUpdate(String gatewayOrderId);

    /** The top-up by ID, row-locked until the transaction ends. */
    Optional<Topup> findByIdForUpdate(UUID id);

    /** At most {@code limit} PENDING top-ups created before {@code createdBefore}, oldest first (FR-P-6). */
    List<Topup> findPendingCreatedBefore(Instant createdBefore, int limit);

    /**
     * Sum in minor units of the wallet's top-ups created at or after {@code fromInclusive} with one of the statuses
     * (0 if none). For a consistent limit check the caller holds the wallet's row lock (see {@code TopupLimitChecker}).
     */
    long sumCreatedSince(UUID walletId, Instant fromInclusive, Collection<TopupStatus> statuses);

    /** Every top-up of the wallet, oldest first (a data export, FR-PR-2). */
    List<Topup> findByWalletIdOrderByCreatedAtAscIdAsc(UUID walletId);

    /** Sum in minor units of all the wallet's top-ups with one of the statuses (0 if none). */
    long sumByStatus(UUID walletId, Collection<TopupStatus> statuses);
}
