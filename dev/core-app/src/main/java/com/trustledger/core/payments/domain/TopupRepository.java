package com.trustledger.core.payments.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Port for top-ups; implemented by Spring Data in {@code payments.infrastructure}. */
public interface TopupRepository {

    Topup save(Topup topup);

    Topup saveAndFlush(Topup topup);

    Optional<Topup> findById(UUID id);

    /**
     * Sum in minor units of the wallet's top-ups created at or after {@code fromInclusive} with one of the statuses
     * (0 if none). For a consistent limit check the caller holds the wallet's row lock (see {@code TopupLimitChecker}).
     */
    long sumCreatedSince(UUID walletId, Instant fromInclusive, Collection<TopupStatus> statuses);

    /** Sum in minor units of all the wallet's top-ups with one of the statuses (0 if none). */
    long sumByStatus(UUID walletId, Collection<TopupStatus> statuses);
}
